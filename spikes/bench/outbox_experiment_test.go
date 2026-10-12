package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
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

func TestOutboxArrivalsCommitOnScheduleAndObserveTheWholeInputWindow(test *testing.T) {
	synctest.Test(test, func(test *testing.T) {
		started := time.Now()
		var arrived []time.Duration
		report, err := paceOutboxArrivals(test.Context(), 4, 250*time.Millisecond, 10*time.Millisecond,
			func(ctx context.Context, index int, occurredAt time.Time) error {
				if index != len(arrived) || !occurredAt.Equal(time.Now()) || ctx.Err() != nil {
					test.Fatalf("incorrect arrival index=%d time=%s", index, occurredAt)
				}
				arrived = append(arrived, time.Since(started))
				return nil
			})
		if err != nil || report.Committed != 4 || report.MaxScheduleLag != 0 || time.Since(started) != time.Second {
			test.Fatalf("report=%+v error=%v elapsed=%s", report, err, time.Since(started))
		}
		for index, elapsed := range arrived {
			if elapsed != time.Duration(index)*250*time.Millisecond {
				test.Fatalf("arrival %d at %s", index, elapsed)
			}
		}
	})
}

func TestOutboxArrivalFailuresRetainOnlyCommittedCounts(test *testing.T) {
	synctest.Test(test, func(test *testing.T) {
		failure := errors.New("insert failed")
		report, err := paceOutboxArrivals(test.Context(), 4, time.Second, 10*time.Millisecond,
			func(_ context.Context, index int, _ time.Time) error {
				if index == 2 {
					return failure
				}
				return nil
			})
		if !errors.Is(err, failure) || report.Committed != 2 || report.FinishedAt.Sub(report.StartedAt) != 2*time.Second {
			test.Fatalf("report=%+v error=%v", report, err)
		}
	})
}

func TestOutboxArrivalGeneratorCannotHideMissedLoadByCatchingUpInABurst(test *testing.T) {
	synctest.Test(test, func(test *testing.T) {
		report, err := paceOutboxArrivals(test.Context(), 4, time.Second, 50*time.Millisecond,
			func(context.Context, int, time.Time) error {
				time.Sleep(2 * time.Second)
				return nil
			})
		if err == nil || report.Committed != 1 || report.MaxScheduleLag != 2*time.Second {
			test.Fatalf("slow generator was accepted: report=%+v error=%v", report, err)
		}
	})
}

func TestOutboxArrivalLagBudgetIncludesTheFinalCommitAndItsExactBoundary(test *testing.T) {
	for _, delay := range []time.Duration{50 * time.Millisecond, 50*time.Millisecond + time.Nanosecond} {
		synctest.Test(test, func(test *testing.T) {
			report, err := paceOutboxArrivals(test.Context(), 1, time.Second, 50*time.Millisecond,
				func(context.Context, int, time.Time) error {
					time.Sleep(delay)
					return nil
				})
			if (err != nil) != (delay > 50*time.Millisecond) || report.Committed != 1 || report.MaxScheduleLag != delay {
				test.Fatalf("delay=%s report=%+v error=%v", delay, report, err)
			}
		})
	}
}

func TestOutboxArrivalsRespectCancellationBeforeAndBetweenCommits(test *testing.T) {
	for _, alreadyCancelled := range []bool{true, false} {
		synctest.Test(test, func(test *testing.T) {
			ctx, cancel := context.WithCancel(test.Context())
			defer cancel()
			if alreadyCancelled {
				cancel()
			} else {
				time.AfterFunc(500*time.Millisecond, cancel)
			}
			report, err := paceOutboxArrivals(ctx, 4, time.Second, 10*time.Millisecond,
				func(context.Context, int, time.Time) error { return nil })
			want := 1
			if alreadyCancelled {
				want = 0
			}
			if !errors.Is(err, context.Canceled) || report.Committed != want {
				test.Fatalf("report=%+v error=%v", report, err)
			}
		})
	}
}

func TestOutboxSustainedRunDistinguishesKeepingUpFromDrainingLater(test *testing.T) {
	for _, publishTime := range []time.Duration{5 * time.Millisecond, 1500 * time.Millisecond} {
		synctest.Test(test, func(test *testing.T) {
			var mutex sync.Mutex
			pending := 0
			var committed, delivered []int
			insert := func(_ context.Context, index int, _ time.Time) error {
				mutex.Lock()
				defer mutex.Unlock()
				pending++
				committed = append(committed, index)
				return nil
			}
			batch := func(ctx context.Context) (int, error) {
				mutex.Lock()
				available := pending > 0
				mutex.Unlock()
				if !available {
					return 0, nil
				}
				if err := waitForOutboxArrival(ctx, time.Now().Add(publishTime)); err != nil {
					return 0, err
				}
				mutex.Lock()
				defer mutex.Unlock()
				pending--
				delivered = append(delivered, committed[len(delivered)])
				return 1, nil
			}
			report, err := sustainOutboxExperiment(test.Context(), 4, time.Second, 10*time.Millisecond, insert, batch)
			if err != nil || report.Arrivals.Committed != 4 || report.Published != 4 || report.InputSeconds != 4 ||
				report.InputWallSeconds != 4 || report.CommittedEventsPerSecond != 1 || pending != 0 {
				test.Fatalf("report=%+v pending=%d error=%v", report, pending, err)
			}
			if publishTime < time.Second {
				if !report.CaughtUpAtInputEnd || report.PublishedDuringInput != 4 || report.BacklogAtInputEnd != 0 ||
					report.TailDrainSeconds != 0 || report.PublishedEventsPerSecond != 1 {
					test.Fatalf("fast relay not caught up: %+v", report)
				}
			} else if report.CaughtUpAtInputEnd || report.PublishedDuringInput != 2 || report.BacklogAtInputEnd != 2 ||
				report.TailDrainSeconds < 2 || report.PublishedEventsPerSecond != 0.5 {
				test.Fatalf("slow relay hidden by tail drain: %+v", report)
			}
			for index, event := range delivered {
				if index != event {
					test.Fatalf("delivery reordered: %v", delivered)
				}
			}
		})
	}
}

func TestOutboxSustainedFailureCancelsAndJoinsTheOtherActivity(test *testing.T) {
	for _, failedSide := range []string{"database", "relay"} {
		synctest.Test(test, func(test *testing.T) {
			failure := errors.New("injected " + failedSide + " failure")
			var inserts, batches atomic.Int32
			insert := func(ctx context.Context, index int, _ time.Time) error {
				inserts.Add(1)
				if failedSide == "database" && index == 1 {
					return failure
				}
				return ctx.Err()
			}
			batch := func(ctx context.Context) (int, error) {
				batches.Add(1)
				if failedSide == "relay" {
					return 0, failure
				}
				<-ctx.Done()
				return 0, ctx.Err()
			}
			report, err := sustainOutboxExperiment(test.Context(), 4, time.Second, 10*time.Millisecond, insert, batch)
			if !errors.Is(err, failure) || report.Published != 0 || report.CaughtUpAtInputEnd {
				test.Fatalf("report=%+v error=%v", report, err)
			}
			insertCount, batchCount := inserts.Load(), batches.Load()
			time.Sleep(time.Minute)
			if inserts.Load() != insertCount || batches.Load() != batchCount {
				test.Fatal("work continued after the sustained run returned")
			}
		})
	}
}

func TestOutboxSustainedIdleRelayStopsAtTheOverallDeadline(test *testing.T) {
	synctest.Test(test, func(test *testing.T) {
		ctx, cancel := context.WithTimeout(test.Context(), 3*time.Second)
		defer cancel()
		report, err := sustainOutboxExperiment(ctx, 2, time.Second, 10*time.Millisecond,
			func(context.Context, int, time.Time) error { return nil },
			func(context.Context) (int, error) { return 0, nil })
		if !errors.Is(err, context.DeadlineExceeded) || report.Arrivals.Committed != 2 || report.Published != 0 ||
			report.BacklogAtInputEnd != 2 || report.CaughtUpAtInputEnd || report.TailDrainSeconds != 1 {
			test.Fatalf("idle relay report=%+v error=%v", report, err)
		}
	})
}

func TestOutboxSustainedInputRejectsClockGapsInsteadOfCertifyingAnInterruptedRun(test *testing.T) {
	for _, sample := range []struct {
		monotonic time.Duration
		wall      time.Duration
		valid     bool
	}{
		{monotonic: time.Second, wall: time.Second, valid: true},
		{monotonic: time.Second, wall: time.Second + 100*time.Millisecond, valid: true},
		{monotonic: time.Second, wall: time.Second - 100*time.Millisecond, valid: true},
		{monotonic: time.Second, wall: time.Second + 100*time.Millisecond + time.Nanosecond},
		{monotonic: time.Second, wall: time.Second - 100*time.Millisecond - time.Nanosecond},
		{monotonic: time.Second, wall: time.Minute},
		{monotonic: 0, wall: time.Second},
		{monotonic: time.Second, wall: 0},
	} {
		if err := validateOutboxInputWindow(sample.monotonic, sample.wall, 100*time.Millisecond); (err == nil) != sample.valid {
			test.Fatalf("sample=%+v error=%v", sample, err)
		}
	}
}

func TestOutboxPacingRejectsInvalidFunctionsAndOverflowBeforeDoingWork(test *testing.T) {
	insert := func(context.Context, int, time.Time) error {
		test.Fatal("invalid workload invoked the database")
		return nil
	}
	for _, specification := range []struct {
		events   int
		interval time.Duration
		lag      time.Duration
	}{
		{events: 0, interval: time.Second},
		{events: 1, interval: 0},
		{events: 1, interval: -time.Second},
		{events: 1, interval: time.Second, lag: -time.Nanosecond},
		{events: 2, interval: time.Duration(1<<63 - 1)},
	} {
		if _, err := paceOutboxArrivals(test.Context(), specification.events, specification.interval, specification.lag, insert); err == nil {
			test.Fatalf("accepted invalid pacing: %+v", specification)
		}
	}
	if _, err := paceOutboxArrivals(test.Context(), 1, time.Second, 0, nil); err == nil {
		test.Fatal("accepted missing insert function")
	}
	if _, err := sustainOutboxExperiment(test.Context(), 1, time.Second, 0, insert, nil); err == nil {
		test.Fatal("accepted missing relay function")
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
		func(options *outboxExperimentOptions) { options.arrivalInterval = -time.Second },
		func(options *outboxExperimentOptions) { options.maxArrivalLag = -time.Nanosecond },
		func(options *outboxExperimentOptions) { options.arrivalInterval = options.timeout },
		func(options *outboxExperimentOptions) { options.arrivalInterval = time.Duration(1<<63 - 1) },
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
		{"-arrival-interval", "-1ms"}, {"-max-arrival-lag", "-1ns"}, {"-arrival-interval", "1s"},
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

	options.output = filepath.Join(test.TempDir(), "paced-experiment")
	options.arrivalInterval = 100 * time.Millisecond
	options.maxArrivalLag = 500 * time.Millisecond
	steady, err := executeOutboxExperiment(ctx, options)
	if err != nil || !steady.Success || steady.Stage != "complete" || steady.Workload != "paced-arrivals" ||
		steady.Sustained == nil || steady.Seeded != 18 || steady.Published != 18 ||
		steady.Verification == nil || !steady.Verification.ok() || steady.Verification.Unique != 18 {
		test.Fatalf("paced experiment report=%+v error=%v", steady, err)
	}
	if steady.Sustained.InputSeconds < 1.8 || steady.Sustained.InputWallSeconds < 1.8 ||
		steady.Sustained.Arrivals.MaxScheduleLag > options.maxArrivalLag || steady.DrainEventsPerSecond != 0 ||
		steady.Sustained.BacklogAtInputEnd != options.events-steady.Sustained.PublishedDuringInput {
		test.Fatalf("paced timing or accounting is invalid: %+v", steady)
	}
	if err := unrelatedStore.pool.QueryRow(ctx, "SELECT to_regnamespace($1) IS NULL", steady.Schema).Scan(&isolated); err != nil || !isolated {
		test.Fatalf("paced schema cleanup failed: removed=%v error=%v", isolated, err)
	}
}

func TestOutboxExperimentRetainsPartialArrivalsWhenTheGeneratorMissesItsBudget(test *testing.T) {
	brokers, _, ctx := kafkaOutboxTestTopic(test)
	unrelatedStore, _ := postgresOutboxTestStore(test)
	options := outboxExperimentTestOptions(filepath.Join(test.TempDir(), "paced-failure"))
	options.pgURL = os.Getenv("SPIKE_TEST_PG")
	options.brokers = brokers
	options.arrivalInterval = 10 * time.Millisecond
	options.maxArrivalLag = time.Nanosecond
	report, err := executeOutboxExperiment(ctx, options)
	if err == nil || report.Success || report.Stage != "sustained" || report.Sustained == nil ||
		report.Sustained.CaughtUpAtInputEnd || report.Seeded >= int64(options.events) {
		test.Fatalf("invalid workload accepted: report=%+v error=%v", report, err)
	}
	ledger, err := os.ReadFile(filepath.Join(options.output, "expected.jsonl"))
	if err != nil {
		test.Fatal(err)
	}
	count := bytes.Count(ledger, []byte("\n"))
	if int64(count) != report.Seeded {
		test.Fatalf("partial committed evidence lost: rows=%d report=%+v", count, report)
	}
	content, err := os.ReadFile(filepath.Join(options.output, "report.json"))
	if err != nil {
		test.Fatal(err)
	}
	var saved outboxExperimentReport
	if err := json.Unmarshal(content, &saved); err != nil || saved.Success || saved.Sustained == nil {
		test.Fatalf("failure not saved: %s error=%v", content, err)
	}
	var removed bool
	if err := unrelatedStore.pool.QueryRow(ctx, "SELECT to_regnamespace($1) IS NULL", report.Schema).Scan(&removed); err != nil || !removed {
		test.Fatalf("failed paced schema cleanup: removed=%v error=%v", removed, err)
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

	options.output = filepath.Join(test.TempDir(), "failed-paced-publication")
	options.arrivalInterval = 100 * time.Millisecond
	options.maxArrivalLag = 500 * time.Millisecond
	paced, err := executeOutboxExperiment(ctx, options)
	if !errors.Is(err, context.DeadlineExceeded) || paced.Success || paced.Stage != "sustained" || paced.Sustained == nil ||
		paced.Seeded < 1 || paced.Published != 0 {
		test.Fatalf("paced publish failure report=%+v error=%v", paced, err)
	}
	ledger, err = os.ReadFile(filepath.Join(options.output, "expected.jsonl"))
	if err != nil || int64(bytes.Count(ledger, []byte("\n"))) != paced.Seeded {
		test.Fatalf("paced committed ledger lost after publication failure: %q error=%v", ledger, err)
	}
	if err := unrelatedStore.pool.QueryRow(ctx, "SELECT to_regnamespace($1) IS NULL", paced.Schema).Scan(&removed); err != nil || !removed {
		test.Fatalf("paced publish failure cleanup failed: removed=%v error=%v", removed, err)
	}
}
