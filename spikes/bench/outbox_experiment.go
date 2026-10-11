package main

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"
)

type outboxExperimentOptions struct {
	pgURL          string
	brokers        []string
	output         string
	events         int
	aggregates     int
	batchSize      int
	payloadBytes   int
	timeout        time.Duration
	publishTimeout time.Duration
}

func (options outboxExperimentOptions) validate() error {
	if strings.TrimSpace(options.pgURL) == "" || strings.TrimSpace(options.output) == "" || len(options.brokers) == 0 {
		return errors.New("PostgreSQL, brokers and a new output directory are required")
	}
	for _, broker := range options.brokers {
		if strings.TrimSpace(broker) == "" {
			return errors.New("broker addresses must not be empty")
		}
	}
	if options.events < 1 || options.aggregates < 1 || options.aggregates > options.events || options.batchSize < 1 {
		return errors.New("positive event, aggregate and batch counts are required; aggregates must not exceed events")
	}
	if options.payloadBytes < 0 || options.payloadBytes > 64*1024 || options.timeout <= 0 || options.publishTimeout <= 0 {
		return errors.New("payload padding must be 0..65536 bytes and timeouts must be positive")
	}
	return nil
}

func (options outboxExperimentOptions) row(runID string, index int, occurredAt time.Time) (outboxRelayRow, error) {
	if options.aggregates < 1 || index < 0 || index >= options.events || runID == "" {
		return outboxRelayRow{}, errors.New("invalid workload event index, aggregate count or run ID")
	}
	key := fmt.Sprintf("%s-ride-%d", runID, index%options.aggregates)
	envelope, err := json.Marshal(map[string]any{
		"event_id":          fmt.Sprintf("%s-event-%d", runID, index),
		"event_type":        "SpikeChanged",
		"event_version":     1,
		"aggregate_type":    "ride",
		"aggregate_id":      key,
		"aggregate_version": index / options.aggregates,
		"occurred_at":       occurredAt.UTC(),
		"producer":          "spike:worker",
		"correlation_id":    key,
		"causation_id":      nil,
		"trace_parent":      "",
		"payload":           map[string]string{"padding": strings.Repeat("x", options.payloadBytes)},
	})
	if err != nil {
		return outboxRelayRow{}, err
	}
	return outboxRelayRow{id: int64(index) + 1, partitionKey: key, envelope: envelope}, nil
}

func createOutboxExperimentDirectory(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	return os.Mkdir(path, 0700)
}

type outboxExperimentReport struct {
	RunID                string              `json:"run_id"`
	Schema               string              `json:"schema"`
	Topic                string              `json:"topic"`
	Workload             string              `json:"workload"`
	RelayMode            string              `json:"relay_mode"`
	Events               int                 `json:"events"`
	Aggregates           int                 `json:"aggregates"`
	BatchSize            int                 `json:"batch_size"`
	PayloadBytes         int                 `json:"payload_padding_bytes"`
	Partitions           int                 `json:"partitions"`
	ReplicationFactor    int                 `json:"replication_factor"`
	PublishTimeout       string              `json:"publish_timeout"`
	Timeout              string              `json:"timeout"`
	StartedAt            time.Time           `json:"started_at"`
	FinishedAt           time.Time           `json:"finished_at"`
	DrainStartedAt       *time.Time          `json:"drain_started_at,omitempty"`
	DrainFinishedAt      *time.Time          `json:"drain_finished_at,omitempty"`
	Seeded               int64               `json:"seeded"`
	Published            int                 `json:"published"`
	DrainSeconds         float64             `json:"drain_monotonic_seconds"`
	DrainWallSeconds     float64             `json:"drain_wall_seconds"`
	DrainEventsPerSecond float64             `json:"drain_events_per_wall_second"`
	Build                map[string]string   `json:"build"`
	Stage                string              `json:"stage"`
	Success              bool                `json:"success"`
	Capture              *outboxKafkaCapture `json:"capture,omitempty"`
	Verification         *outboxTraceReport  `json:"verification,omitempty"`
}

func drainOutboxExperiment(ctx context.Context, batch func(context.Context) (int, error), expected int) (int, error) {
	if expected < 1 {
		return 0, errors.New("a positive expected event count is required")
	}
	published := 0
	for published < expected {
		if err := ctx.Err(); err != nil {
			return published, err
		}
		count, err := batch(ctx)
		if err != nil {
			return published, err
		}
		if count < 1 || count > expected-published {
			return published, fmt.Errorf("relay returned %d events with %d still expected", count, expected-published)
		}
		published += count
	}
	return published, nil
}

func writeOutboxExperimentJSON(path string, value any) error {
	output, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	encodeErr := json.NewEncoder(output).Encode(value)
	return errors.Join(encodeErr, output.Close())
}

func exportOutboxExperimentLedger(ctx context.Context, store *postgresOutboxStore, path string) (count int, exportErr error) {
	output, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return 0, err
	}
	defer func() { exportErr = errors.Join(exportErr, output.Close()) }()
	rows, err := store.pool.Query(ctx, fmt.Sprintf("SELECT envelope FROM %s ORDER BY id", store.outbox))
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	encoder := json.NewEncoder(output)
	for rows.Next() {
		var envelope []byte
		if err := rows.Scan(&envelope); err != nil {
			return count, err
		}
		if err := encoder.Encode(json.RawMessage(envelope)); err != nil {
			return count, err
		}
		count++
	}
	return count, rows.Err()
}

func waitForOutboxExperimentTopic(ctx context.Context, admin *kadm.Client, topic string) error {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		details, err := admin.ListTopics(ctx, topic)
		if err != nil {
			return err
		}
		detail, exists := details[topic]
		ready := exists && detail.Err == nil && len(detail.Partitions) == 3
		for _, partition := range detail.Partitions {
			ready = ready && partition.Err == nil && partition.Leader >= 0 && len(partition.ISR) > 0
		}
		if ready {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

func executeOutboxExperiment(ctx context.Context, options outboxExperimentOptions) (report outboxExperimentReport, runErr error) {
	if err := options.validate(); err != nil {
		return report, err
	}
	if err := createOutboxExperimentDirectory(options.output); err != nil {
		return report, err
	}
	runID := strings.ToLower(rand.Text())
	report = outboxExperimentReport{RunID: runID, Schema: "spike_s4_" + runID, Topic: "spike-s4-" + runID,
		Workload: "preloaded-backlog", RelayMode: "synchronous-per-record", Events: options.events,
		Aggregates: options.aggregates, BatchSize: options.batchSize, PayloadBytes: options.payloadBytes,
		Partitions: 3, ReplicationFactor: 1, PublishTimeout: options.publishTimeout.String(),
		Timeout: options.timeout.String(), StartedAt: time.Now().UTC(), Stage: "connect-database",
		Build: map[string]string{"go": runtime.Version(), "os": runtime.GOOS, "arch": runtime.GOARCH},
	}
	if build, ok := debug.ReadBuildInfo(); ok {
		for _, setting := range build.Settings {
			if setting.Key == "vcs.revision" || setting.Key == "vcs.modified" {
				report.Build[setting.Key] = setting.Value
			}
		}
		for _, dependency := range build.Deps {
			if dependency.Path == "github.com/twmb/franz-go" || dependency.Path == "github.com/jackc/pgx/v5" {
				report.Build[dependency.Path] = dependency.Version
			}
		}
	}
	defer func() {
		report.FinishedAt = time.Now().UTC()
		report.Success = runErr == nil
		if err := writeOutboxExperimentJSON(filepath.Join(options.output, "report.json"), report); err != nil {
			report.Success = false
			runErr = errors.Join(runErr, fmt.Errorf("write experiment report: %w", err))
		}
	}()
	ctx, cancel := context.WithTimeout(ctx, options.timeout)
	defer cancel()
	pool, err := pgxpool.New(ctx, options.pgURL)
	if err != nil {
		return report, err
	}
	defer pool.Close()
	if err := pool.Ping(ctx); err != nil {
		return report, err
	}
	store, err := newPostgresOutboxStore(pool, report.Schema, 10*time.Second)
	if err != nil {
		return report, err
	}
	report.Stage = "create-schema"
	if _, err := pool.Exec(ctx, "CREATE SCHEMA "+pgx.Identifier{report.Schema}.Sanitize()); err != nil {
		return report, err
	}
	defer func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := pool.Exec(cleanup, "DROP SCHEMA "+pgx.Identifier{report.Schema}.Sanitize()+" CASCADE"); err != nil {
			report.Stage = "cleanup-schema"
			runErr = errors.Join(runErr, err)
		}
	}()
	if err := store.initialize(ctx); err != nil {
		return report, err
	}
	report.Stage = "create-topic"
	adminClient, err := kgo.NewClient(kgo.SeedBrokers(options.brokers...))
	if err != nil {
		return report, err
	}
	defer adminClient.Close()
	admin := kadm.NewClient(adminClient)
	if _, err := admin.CreateTopic(ctx, 3, 1, map[string]*string{
		"cleanup.policy": kadm.StringPtr("delete"), "retention.ms": kadm.StringPtr("3600000"),
	}, report.Topic); err != nil {
		return report, err
	}
	defer func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := admin.DeleteTopic(cleanup, report.Topic); err != nil {
			report.Stage = "cleanup-topic"
			runErr = errors.Join(runErr, err)
		}
	}()
	if err := waitForOutboxExperimentTopic(ctx, admin, report.Topic); err != nil {
		return report, err
	}
	report.Stage = "seed"
	index := 0
	report.Seeded, err = pool.CopyFrom(ctx, pgx.Identifier{report.Schema, "outbox"}, []string{"partition_key", "envelope"},
		pgx.CopyFromFunc(func() ([]any, error) {
			if index == options.events {
				return nil, nil
			}
			row, err := options.row(runID, index, report.StartedAt)
			if err != nil {
				return nil, err
			}
			index++
			return []any{row.partitionKey, row.envelope}, nil
		}))
	if err != nil {
		return report, err
	}
	if report.Seeded != int64(options.events) {
		return report, errors.New("committed seed count differs from the configured workload")
	}
	report.Stage = "export-ledger"
	ledgerPath := filepath.Join(options.output, "expected.jsonl")
	count, err := exportOutboxExperimentLedger(ctx, store, ledgerPath)
	if err != nil {
		return report, err
	}
	if count != options.events {
		return report, errors.New("ledger count differs from the committed workload")
	}
	report.Stage = "acquire-lease"
	lease, acquired, err := store.acquireLease(ctx, "spike-"+runID)
	if err != nil {
		return report, err
	}
	if !acquired {
		return report, errOutboxLeaseLost
	}
	publisher, err := newKafkaOutboxPublisher(options.brokers, report.Topic, options.publishTimeout)
	if err != nil {
		return report, err
	}
	defer publisher.close()
	relay := outboxRelay{store: store, publish: publisher.publish, lease: lease, batchSize: options.batchSize}
	report.Stage = "drain"
	started := time.Now()
	report.Published, err = drainOutboxExperiment(ctx, relay.relayBatch, options.events)
	finished := time.Now()
	startedUTC, finishedUTC := started.UTC(), finished.UTC()
	report.DrainStartedAt, report.DrainFinishedAt = &startedUTC, &finishedUTC
	report.DrainSeconds = finished.Sub(started).Seconds()
	report.DrainWallSeconds = finishedUTC.Sub(startedUTC).Seconds()
	if err != nil {
		return report, err
	}
	if report.DrainWallSeconds <= 0 {
		return report, errors.New("wall clock did not advance while draining")
	}
	report.DrainEventsPerSecond = float64(report.Published) / report.DrainWallSeconds
	pending, err := store.pending(ctx, 1)
	if err != nil {
		return report, err
	}
	if len(pending) != 0 {
		return report, errors.New("unpublished events remain after draining the configured workload")
	}
	publisher.close()
	report.Stage = "capture"
	tracePath := filepath.Join(options.output, "received.jsonl")
	trace, err := os.OpenFile(tracePath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return report, err
	}
	capture, captureErr := captureOutboxKafkaTrace(ctx, options.brokers, report.Topic, trace)
	report.Capture = &capture
	if err := errors.Join(captureErr, trace.Close()); err != nil {
		return report, err
	}
	report.Stage = "verify"
	ledger, err := os.Open(ledgerPath)
	if err != nil {
		return report, err
	}
	defer ledger.Close()
	received, err := os.Open(tracePath)
	if err != nil {
		return report, err
	}
	defer received.Close()
	verification, err := verifyOutboxTrace(ledger, received)
	report.Verification = &verification
	if err != nil {
		return report, err
	}
	if !verification.ok() || verification.Unique != options.events {
		return report, errors.New("experiment trace violates delivery invariants")
	}
	report.Stage = "complete"
	return report, nil
}

func runOutboxExperiment(args []string) error {
	flags := flag.NewFlagSet("outbox-run", flag.ContinueOnError)
	options := outboxExperimentOptions{}
	flags.StringVar(&options.pgURL, "pg", os.Getenv("SPIKE_PG"), "disposable PostgreSQL database URL (defaults to SPIKE_PG)")
	addresses := flags.String("brokers", "localhost:29092", "comma-separated Kafka bootstrap addresses")
	flags.StringVar(&options.output, "output", "", "new private directory for ledger, trace and report")
	flags.IntVar(&options.events, "events", 1000, "events to preload before draining")
	flags.IntVar(&options.aggregates, "aggregates", 100, "interleaved aggregates in the workload")
	flags.IntVar(&options.batchSize, "batch", 500, "maximum rows per relay batch")
	flags.IntVar(&options.payloadBytes, "payload-bytes", 256, "payload padding bytes per event")
	flags.DurationVar(&options.timeout, "timeout", 2*time.Minute, "overall experiment timeout, excluding cleanup")
	flags.DurationVar(&options.publishTimeout, "publish-timeout", 5*time.Second, "maximum wait per Kafka send")
	if err := flags.Parse(args); errors.Is(err, flag.ErrHelp) {
		return nil
	} else if err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return errors.New("unexpected positional arguments")
	}
	for _, broker := range strings.Split(*addresses, ",") {
		options.brokers = append(options.brokers, strings.TrimSpace(broker))
	}
	report, err := executeOutboxExperiment(context.Background(), options)
	if err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(report)
}
