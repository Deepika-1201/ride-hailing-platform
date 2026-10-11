package main

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"
)

type outboxRelayStub struct {
	maintain func(context.Context, outboxRelayLease) (bool, error)
	read     func(context.Context, int) ([]outboxRelayRow, error)
	mark     func(context.Context, outboxRelayLease, []int64) (bool, error)
}

func (store outboxRelayStub) maintainLease(ctx context.Context, lease outboxRelayLease) (bool, error) {
	return store.maintain(ctx, lease)
}

func (store outboxRelayStub) pending(ctx context.Context, limit int) ([]outboxRelayRow, error) {
	return store.read(ctx, limit)
}

func (store outboxRelayStub) markPublished(ctx context.Context, lease outboxRelayLease, ids []int64) (bool, error) {
	return store.mark(ctx, lease, ids)
}

func TestOutboxRelayAcknowledgesEveryEventBeforeMarking(test *testing.T) {
	lease := outboxRelayLease{holder: "first", token: 1}
	rows := []outboxRelayRow{
		{id: 2, partitionKey: "ride-1", envelope: []byte(traceEvent("first", "ride", "ride-1", 0))},
		{id: 5, partitionKey: "ride-1", envelope: []byte(traceEvent("second", "ride", "ride-1", 1))},
	}
	var acknowledged []int64
	marked := false
	store := outboxRelayStub{
		maintain: func(_ context.Context, candidate outboxRelayLease) (bool, error) {
			return candidate == lease, nil
		},
		read: func(_ context.Context, limit int) ([]outboxRelayRow, error) {
			if limit != 2 {
				test.Fatalf("batch limit = %d", limit)
			}
			return rows, nil
		},
		mark: func(_ context.Context, candidate outboxRelayLease, ids []int64) (bool, error) {
			if candidate != lease || !reflect.DeepEqual(ids, []int64{2, 5}) || !reflect.DeepEqual(acknowledged, ids) {
				test.Fatalf("mark before acknowledgement: lease=%+v ids=%v acknowledged=%v", candidate, ids, acknowledged)
			}
			marked = true
			return true, nil
		},
	}
	relay := outboxRelay{store: store, lease: lease, batchSize: 2,
		publish: func(_ context.Context, row outboxRelayRow) error {
			if !reflect.DeepEqual(row, rows[len(acknowledged)]) {
				test.Fatalf("changed event or key: %+v", row)
			}
			acknowledged = append(acknowledged, row.id)
			return nil
		},
	}

	count, err := relay.relayBatch(context.Background())
	if err != nil || count != 2 || !marked {
		test.Fatalf("count=%d marked=%v error=%v", count, marked, err)
	}
}

func TestOutboxRelaySendFailureLeavesWholeBatchPending(test *testing.T) {
	failure := errors.New("broker acknowledgement lost")
	var attempted []int64
	store := outboxRelayStub{
		maintain: func(context.Context, outboxRelayLease) (bool, error) { return true, nil },
		read: func(context.Context, int) ([]outboxRelayRow, error) {
			return []outboxRelayRow{
				{id: 1, partitionKey: "ride-1", envelope: []byte("first")},
				{id: 2, partitionKey: "ride-1", envelope: []byte("second")},
				{id: 3, partitionKey: "ride-1", envelope: []byte("third")},
			}, nil
		},
		mark: func(context.Context, outboxRelayLease, []int64) (bool, error) {
			test.Fatal("marked a batch whose send failed")
			return false, nil
		},
	}
	relay := outboxRelay{store: store, lease: outboxRelayLease{holder: "first", token: 1}, batchSize: 3,
		publish: func(_ context.Context, row outboxRelayRow) error {
			attempted = append(attempted, row.id)
			if row.id == 2 {
				return failure
			}
			return nil
		},
	}

	count, err := relay.relayBatch(context.Background())
	if count != 0 || !errors.Is(err, failure) || !reflect.DeepEqual(attempted, []int64{1, 2}) {
		test.Fatalf("count=%d attempted=%v error=%v", count, attempted, err)
	}
}

func TestOutboxRelayTakeoverReplaysWithoutLossOrReordering(test *testing.T) {
	failure := errors.New("injected failure")
	cases := []struct {
		name       string
		fault      string
		wantError  error
		duplicates int
	}{
		{name: "send failed before delivery", fault: "send", wantError: failure, duplicates: 1},
		{name: "delivery acknowledged only by broker", fault: "ack", wantError: failure, duplicates: 2},
		{name: "takeover between events", fault: "between", wantError: errOutboxLeaseLost, duplicates: 1},
		{name: "takeover before fenced mark", fault: "mark lease", wantError: errOutboxLeaseLost, duplicates: 3},
		{name: "restarted holder has a new token", fault: "same holder", wantError: errOutboxLeaseLost, duplicates: 3},
		{name: "database mark failed", fault: "mark error", wantError: failure, duplicates: 3},
		{name: "cancelled after delivery", fault: "cancel", wantError: context.Canceled, duplicates: 2},
	}
	for _, testCase := range cases {
		test.Run(testCase.name, func(test *testing.T) {
			rows := []outboxRelayRow{
				{id: 1, partitionKey: "ride-1", envelope: []byte(traceEvent("first", "ride", "ride-1", 0))},
				{id: 2, partitionKey: "ride-1", envelope: []byte(traceEvent("second", "ride", "ride-1", 1))},
				{id: 3, partitionKey: "ride-1", envelope: []byte(traceEvent("third", "ride", "ride-1", 2))},
			}
			owner := outboxRelayLease{holder: "first", token: 1}
			published := false
			injected := false
			var received strings.Builder
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			store := outboxRelayStub{
				maintain: func(_ context.Context, candidate outboxRelayLease) (bool, error) {
					return candidate == owner, nil
				},
				read: func(context.Context, int) ([]outboxRelayRow, error) {
					if published {
						return nil, nil
					}
					return rows, nil
				},
				mark: func(_ context.Context, candidate outboxRelayLease, ids []int64) (bool, error) {
					if candidate != owner {
						return false, nil
					}
					if testCase.fault == "mark error" && !injected {
						injected = true
						return false, failure
					}
					if !reflect.DeepEqual(ids, []int64{1, 2, 3}) {
						test.Fatalf("marked partial batch: %v", ids)
					}
					published = true
					return true, nil
				},
			}
			relay := outboxRelay{store: store, lease: owner, batchSize: 3,
				publish: func(_ context.Context, row outboxRelayRow) error {
					if testCase.fault == "send" && row.id == 2 && !injected {
						injected = true
						return failure
					}
					received.Write(row.envelope)
					if injected {
						return nil
					}
					switch {
					case testCase.fault == "ack" && row.id == 2:
						injected = true
						return failure
					case testCase.fault == "between" && row.id == 1,
						testCase.fault == "mark lease" && row.id == 3:
						injected = true
						owner = outboxRelayLease{holder: "second", token: 2}
					case testCase.fault == "same holder" && row.id == 3:
						injected = true
						owner.token++
					case testCase.fault == "cancel" && row.id == 2:
						injected = true
						cancel()
					}
					return nil
				},
			}

			count, err := relay.relayBatch(ctx)
			if count != 0 || !errors.Is(err, testCase.wantError) || published || !injected {
				test.Fatalf("first batch count=%d published=%v injected=%v error=%v", count, published, injected, err)
			}
			owner.token++
			relay.lease = owner
			count, err = relay.relayBatch(context.Background())
			if count != 3 || err != nil || !published {
				test.Fatalf("successor count=%d published=%v error=%v", count, published, err)
			}
			var expected strings.Builder
			for _, row := range rows {
				expected.Write(row.envelope)
			}
			report, err := verifyOutboxTrace(strings.NewReader(expected.String()), strings.NewReader(received.String()))
			if err != nil || !report.ok() || report.Unique != 3 || report.Duplicates != testCase.duplicates {
				test.Fatalf("trace report=%+v error=%v", report, err)
			}
		})
	}
}

func TestOutboxRelayReadsLateCommitBelowPreviousBatchIDs(test *testing.T) {
	rows := []outboxRelayRow{{id: 20, partitionKey: "ride-1", envelope: []byte("first commit")}}
	var delivered []int64
	store := outboxRelayStub{
		maintain: func(context.Context, outboxRelayLease) (bool, error) { return true, nil },
		read:     func(context.Context, int) ([]outboxRelayRow, error) { return rows, nil },
		mark: func(_ context.Context, _ outboxRelayLease, ids []int64) (bool, error) {
			if len(rows) != 1 || !reflect.DeepEqual(ids, []int64{rows[0].id}) {
				test.Fatalf("unexpected mark: %v", ids)
			}
			rows = nil
			return true, nil
		},
	}
	relay := outboxRelay{store: store, lease: outboxRelayLease{holder: "first", token: 1}, batchSize: 1,
		publish: func(_ context.Context, row outboxRelayRow) error {
			delivered = append(delivered, row.id)
			return nil
		},
	}
	if count, err := relay.relayBatch(context.Background()); count != 1 || err != nil {
		test.Fatalf("first count=%d error=%v", count, err)
	}
	rows = []outboxRelayRow{{id: 10, partitionKey: "ride-2", envelope: []byte("late commit")}}
	if count, err := relay.relayBatch(context.Background()); count != 1 || err != nil {
		test.Fatalf("late count=%d error=%v", count, err)
	}
	if count, err := relay.relayBatch(context.Background()); count != 0 || err != nil {
		test.Fatalf("idle count=%d error=%v", count, err)
	}
	if !reflect.DeepEqual(delivered, []int64{20, 10}) {
		test.Fatalf("late commit skipped: %v", delivered)
	}
}

func TestOutboxRelayRejectsInvalidStoreBatches(test *testing.T) {
	valid := outboxRelayRow{id: 1, partitionKey: "ride-1", envelope: []byte("event")}
	cases := []struct {
		name string
		rows []outboxRelayRow
	}{
		{name: "oversized", rows: []outboxRelayRow{valid, valid, valid}},
		{name: "duplicate IDs", rows: []outboxRelayRow{valid, valid}},
		{name: "decreasing IDs", rows: []outboxRelayRow{{id: 2, partitionKey: "ride-1", envelope: valid.envelope}, valid}},
		{name: "zero ID", rows: []outboxRelayRow{{partitionKey: "ride-1", envelope: valid.envelope}}},
		{name: "missing key", rows: []outboxRelayRow{{id: 1, envelope: valid.envelope}}},
		{name: "missing envelope", rows: []outboxRelayRow{{id: 1, partitionKey: "ride-1"}}},
	}
	for _, testCase := range cases {
		test.Run(testCase.name, func(test *testing.T) {
			store := outboxRelayStub{
				maintain: func(context.Context, outboxRelayLease) (bool, error) { return true, nil },
				read:     func(context.Context, int) ([]outboxRelayRow, error) { return testCase.rows, nil },
				mark: func(context.Context, outboxRelayLease, []int64) (bool, error) {
					test.Fatal("marked invalid input")
					return false, nil
				},
			}
			relay := outboxRelay{store: store, lease: outboxRelayLease{holder: "first", token: 1}, batchSize: 2,
				publish: func(context.Context, outboxRelayRow) error {
					test.Fatal("published invalid input")
					return nil
				},
			}
			if count, err := relay.relayBatch(context.Background()); count != 0 || err == nil {
				test.Fatalf("count=%d error=%v", count, err)
			}
		})
	}
}

func TestOutboxRelayStopsOnLeaseAndReadErrors(test *testing.T) {
	failure := errors.New("database unavailable")
	for _, stage := range []string{"lease lost", "lease error", "read error"} {
		test.Run(stage, func(test *testing.T) {
			wanted := failure
			store := outboxRelayStub{
				maintain: func(context.Context, outboxRelayLease) (bool, error) {
					if stage == "lease lost" {
						wanted = errOutboxLeaseLost
						return false, nil
					}
					if stage == "lease error" {
						return false, failure
					}
					return true, nil
				},
				read: func(context.Context, int) ([]outboxRelayRow, error) {
					if stage != "read error" {
						test.Fatal("read without a lease")
					}
					return nil, failure
				},
			}
			relay := outboxRelay{store: store, lease: outboxRelayLease{holder: "first", token: 1}, batchSize: 1}
			if count, err := relay.relayBatch(context.Background()); count != 0 || !errors.Is(err, wanted) {
				test.Fatalf("count=%d error=%v", count, err)
			}
		})
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	relay := outboxRelay{lease: outboxRelayLease{holder: "first", token: 1}, batchSize: 1}
	if count, err := relay.relayBatch(ctx); count != 0 || !errors.Is(err, context.Canceled) {
		test.Fatalf("cancelled count=%d error=%v", count, err)
	}
	for _, invalid := range []outboxRelay{
		{batchSize: 0, lease: relay.lease},
		{batchSize: -1, lease: relay.lease},
		{batchSize: 1, lease: outboxRelayLease{token: 1}},
		{batchSize: 1, lease: outboxRelayLease{holder: "first"}},
	} {
		if count, err := invalid.relayBatch(context.Background()); count != 0 || err == nil {
			test.Fatalf("invalid runner count=%d error=%v", count, err)
		}
	}
}
