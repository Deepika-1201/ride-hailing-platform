package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"
)

func outboxExperimentTestOptions(output string) outboxExperimentOptions {
	return outboxExperimentOptions{pgURL: "postgres://example", brokers: []string{"localhost:29092"}, output: output,
		events: 18, aggregates: 3, batchSize: 5, payloadBytes: 64, timeout: time.Minute, publishTimeout: 5 * time.Second}
}

func TestOutboxExperimentWorkloadIsOrderedAndDeterministic(test *testing.T) {
	options := outboxExperimentTestOptions("unused")
	occurredAt := time.Date(2026, 10, 11, 0, 0, 0, 0, time.UTC)
	var ledger bytes.Buffer
	for index := range options.events {
		row, err := options.row("test-run", index, occurredAt)
		if err != nil {
			test.Fatal(err)
		}
		repeated, err := options.row("test-run", index, occurredAt)
		if err != nil || !bytes.Equal(row.envelope, repeated.envelope) || row.id != int64(index)+1 {
			test.Fatalf("non-deterministic event %d: error=%v", index, err)
		}
		if _, err := outboxKafkaRecord("spike-s4-workload", row); err != nil {
			test.Fatal(err)
		}
		var event struct {
			outboxTraceEvent
			Payload struct{ Padding string }
		}
		if err := json.Unmarshal(row.envelope, &event); err != nil {
			test.Fatal(err)
		}
		if event.AggregateID != row.partitionKey || *event.AggregateVersion != int64(index/options.aggregates) || len(event.Payload.Padding) != options.payloadBytes {
			test.Fatalf("incorrect workload event %d: %+v", index, event)
		}
		ledger.Write(row.envelope)
		ledger.WriteByte('\n')
	}
	report, err := verifyOutboxTrace(bytes.NewReader(ledger.Bytes()), bytes.NewReader(ledger.Bytes()))
	if err != nil || !report.ok() || report.Unique != options.events || report.Duplicates != 0 {
		test.Fatalf("report=%+v error=%v", report, err)
	}
}

func TestOutboxExperimentRejectsUnsafeOptions(test *testing.T) {
	valid := outboxExperimentTestOptions("unused")
	if err := valid.validate(); err != nil {
		test.Fatal(err)
	}
	for _, change := range []func(*outboxExperimentOptions){
		func(options *outboxExperimentOptions) { options.pgURL = "" },
		func(options *outboxExperimentOptions) { options.output = "" },
		func(options *outboxExperimentOptions) { options.brokers = nil },
		func(options *outboxExperimentOptions) { options.brokers = []string{" "} },
		func(options *outboxExperimentOptions) { options.events = 0 },
		func(options *outboxExperimentOptions) { options.aggregates = 0 },
		func(options *outboxExperimentOptions) { options.aggregates = options.events + 1 },
		func(options *outboxExperimentOptions) { options.batchSize = 0 },
		func(options *outboxExperimentOptions) { options.payloadBytes = -1 },
		func(options *outboxExperimentOptions) { options.payloadBytes = 65537 },
		func(options *outboxExperimentOptions) { options.timeout = 0 },
		func(options *outboxExperimentOptions) { options.publishTimeout = 0 },
	} {
		invalid := valid
		change(&invalid)
		if err := invalid.validate(); err == nil {
			test.Errorf("accepted invalid options: %+v", invalid)
		}
	}
}

func TestOutboxExperimentOutputIsPrivateAndNeverReused(test *testing.T) {
	path := filepath.Join(test.TempDir(), "runs", "first")
	if err := createOutboxExperimentDirectory(path); err != nil {
		test.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0700 {
		test.Fatalf("output is not private: info=%v error=%v", info, err)
	}
	evidence := filepath.Join(path, "report.json")
	if err := os.WriteFile(evidence, []byte("preserve this run"), 0600); err != nil {
		test.Fatal(err)
	}
	if err := createOutboxExperimentDirectory(path); !errors.Is(err, os.ErrExist) {
		test.Fatalf("reused output directory: %v", err)
	}
	if content, err := os.ReadFile(evidence); err != nil || string(content) != "preserve this run" {
		test.Fatalf("earlier evidence changed: %q error=%v", content, err)
	}
}

func TestOutboxExperimentDrainCountsOnlyCompletedBatches(test *testing.T) {
	failure := errors.New("injected relay failure")
	for _, testCase := range []struct {
		name      string
		batches   []int
		failureAt int
		want      int
		wantError bool
	}{
		{name: "partial final batch", batches: []int{5, 5, 3}, failureAt: -1, want: 13},
		{name: "idle too early", batches: []int{5, 0}, failureAt: -1, want: 5, wantError: true},
		{name: "negative count", batches: []int{5, -1}, failureAt: -1, want: 5, wantError: true},
		{name: "oversized count", batches: []int{5, 9}, failureAt: -1, want: 5, wantError: true},
		{name: "send failed", batches: []int{5, 5}, failureAt: 1, want: 5, wantError: true},
	} {
		test.Run(testCase.name, func(test *testing.T) {
			index := 0
			count, err := drainOutboxExperiment(test.Context(), func(context.Context) (int, error) {
				if index >= len(testCase.batches) {
					test.Fatal("extra batch after the configured workload")
				}
				if index == testCase.failureAt {
					return 0, failure
				}
				count := testCase.batches[index]
				index++
				return count, nil
			}, 13)
			if count != testCase.want || (err != nil) != testCase.wantError {
				test.Fatalf("published=%d error=%v", count, err)
			}
			if testCase.failureAt >= 0 && !errors.Is(err, failure) {
				test.Fatalf("relay error was hidden: %v", err)
			}
		})
	}
	ctx, cancel := context.WithCancel(test.Context())
	cancel()
	if count, err := drainOutboxExperiment(ctx, nil, 1); count != 0 || !errors.Is(err, context.Canceled) {
		test.Fatalf("cancelled drain: count=%d error=%v", count, err)
	}
}

func TestOutboxExperimentRecordsSetupFailureWithoutSecrets(test *testing.T) {
	options := outboxExperimentTestOptions(filepath.Join(test.TempDir(), "failed"))
	options.pgURL = "postgres://user:private-password@["
	report, err := executeOutboxExperiment(test.Context(), options)
	if err == nil || report.Success || report.Stage != "connect-database" {
		test.Fatalf("failed run report=%+v error=%v", report, err)
	}
	content, err := os.ReadFile(filepath.Join(options.output, "report.json"))
	if err != nil {
		test.Fatal(err)
	}
	var saved outboxExperimentReport
	if err := json.Unmarshal(content, &saved); err != nil {
		test.Fatal(err)
	}
	if saved.Success || saved.Stage != report.Stage || saved.FinishedAt.IsZero() || bytes.Contains(content, []byte("private-password")) {
		test.Fatalf("incorrect or unsafe failure report: %s", content)
	}
}

func TestOutboxExperimentCommandValidatesBeforeCreatingOutput(test *testing.T) {
	if err := runOutboxExperiment([]string{"-h"}); err != nil {
		test.Fatal(err)
	}
	path := filepath.Join(test.TempDir(), "run")
	for _, extra := range [][]string{
		{"-events", "0"}, {"-aggregates", "1001"}, {"-batch", "0"}, {"-payload-bytes", "-1"},
		{"-timeout", "0s"}, {"-publish-timeout", "0s"}, {"-brokers", "localhost:29092,"}, {"extra"},
	} {
		args := append([]string{"-pg", "postgres://example", "-output", path}, extra...)
		if err := runOutboxExperiment(args); err == nil {
			test.Errorf("accepted invalid arguments %v", args)
		}
		if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
			test.Fatalf("invalid arguments created output: %v", err)
		}
	}
	if err := os.Mkdir(path, 0700); err != nil {
		test.Fatal(err)
	}
	if err := runOutboxExperiment([]string{"-pg", "postgres://example", "-output", path}); !errors.Is(err, os.ErrExist) {
		test.Fatalf("command reused an existing directory: %v", err)
	}
}

func TestOutboxExperimentEndToEndArtifactsAndScopedCleanup(test *testing.T) {
	brokers, unrelatedTopic, ctx := kafkaOutboxTestTopic(test)
	unrelatedStore, _ := postgresOutboxTestStore(test)
	options := outboxExperimentTestOptions(filepath.Join(test.TempDir(), "experiment"))
	options.pgURL = os.Getenv("SPIKE_TEST_PG")
	options.brokers = brokers
	report, err := executeOutboxExperiment(ctx, options)
	if err != nil || !report.Success || report.Stage != "complete" {
		test.Fatalf("experiment report=%+v error=%v", report, err)
	}
	if report.Seeded != 18 || report.Published != 18 || report.Capture == nil || report.Capture.Records != 18 ||
		report.Verification == nil || !report.Verification.ok() || report.Verification.Unique != 18 || report.Verification.Duplicates != 0 {
		test.Fatalf("incomplete experiment report: %+v", report)
	}
	if report.Workload != "preloaded-backlog" || report.RelayMode != "synchronous-per-record" ||
		report.DrainSeconds <= 0 || report.DrainWallSeconds <= 0 || report.DrainEventsPerSecond <= 0 ||
		report.DrainStartedAt == nil || report.DrainFinishedAt == nil || report.StartedAt.After(*report.DrainStartedAt) ||
		report.FinishedAt.Before(*report.DrainFinishedAt) {
		test.Fatalf("incorrect experiment timing metadata: %+v", report)
	}
	for _, name := range []string{"expected.jsonl", "received.jsonl", "report.json"} {
		info, err := os.Stat(filepath.Join(options.output, name))
		if err != nil || info.Size() == 0 || info.Mode().Perm() != 0600 {
			test.Fatalf("invalid artifact %s: info=%v error=%v", name, info, err)
		}
	}
	content, err := os.ReadFile(filepath.Join(options.output, "report.json"))
	if err != nil {
		test.Fatal(err)
	}
	var saved outboxExperimentReport
	if err := json.Unmarshal(content, &saved); err != nil {
		test.Fatal(err)
	}
	if !saved.Success || saved.RunID != report.RunID || saved.Verification == nil || !saved.Verification.ok() {
		test.Fatalf("saved report differs: %s", content)
	}
	var isolated bool
	if err := unrelatedStore.pool.QueryRow(ctx, "SELECT to_regnamespace($1) IS NULL AND to_regnamespace($2) IS NOT NULL",
		report.Schema, unrelatedStore.schema).Scan(&isolated); err != nil || !isolated {
		test.Fatalf("schema cleanup was incomplete or exceeded its scope: isolated=%v error=%v", isolated, err)
	}
	client, err := kgo.NewClient(kgo.SeedBrokers(brokers...))
	if err != nil {
		test.Fatal(err)
	}
	defer client.Close()
	details, err := kadm.NewClient(client).ListTopics(ctx, unrelatedTopic)
	if err != nil || !details.Has(unrelatedTopic) || details[unrelatedTopic].Err != nil {
		test.Fatalf("unrelated topic affected by cleanup: details=%+v error=%v", details, err)
	}
	if _, err := executeOutboxExperiment(ctx, options); !errors.Is(err, os.ErrExist) {
		test.Fatalf("experiment reused its output: %v", err)
	}
}

func TestOutboxExperimentRetainsEvidenceAndCleansUpAfterPublishFailure(test *testing.T) {
	brokers, _, ctx := kafkaOutboxTestTopic(test)
	unrelatedStore, _ := postgresOutboxTestStore(test)
	options := outboxExperimentTestOptions(filepath.Join(test.TempDir(), "failed-experiment"))
	options.pgURL = os.Getenv("SPIKE_TEST_PG")
	options.brokers = brokers
	options.publishTimeout = time.Nanosecond
	report, err := executeOutboxExperiment(ctx, options)
	if !errors.Is(err, context.DeadlineExceeded) || report.Success || report.Stage != "drain" || report.Seeded != 18 || report.Published != 0 {
		test.Fatalf("failed experiment report=%+v error=%v", report, err)
	}
	ledger, err := os.ReadFile(filepath.Join(options.output, "expected.jsonl"))
	if err != nil {
		test.Fatal(err)
	}
	verification, err := verifyOutboxTrace(bytes.NewReader(ledger), bytes.NewReader(ledger))
	if err != nil || !verification.ok() || verification.Expected != 18 {
		test.Fatalf("committed ledger not retained: verification=%+v error=%v", verification, err)
	}
	content, err := os.ReadFile(filepath.Join(options.output, "report.json"))
	if err != nil {
		test.Fatal(err)
	}
	var saved outboxExperimentReport
	if err := json.Unmarshal(content, &saved); err != nil {
		test.Fatal(err)
	}
	if saved.Success || saved.Stage != "drain" || saved.Seeded != 18 || saved.Verification != nil || saved.DrainEventsPerSecond != 0 || saved.PublishTimeout != "1ns" {
		test.Fatalf("failure recorded as a valid result: %s", content)
	}
	var removed bool
	if err := unrelatedStore.pool.QueryRow(ctx, "SELECT to_regnamespace($1) IS NULL", report.Schema).Scan(&removed); err != nil || !removed {
		test.Fatalf("failed-run schema not cleaned up: removed=%v error=%v", removed, err)
	}
}
