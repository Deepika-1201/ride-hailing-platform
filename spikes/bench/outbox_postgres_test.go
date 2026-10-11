package main

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func postgresOutboxTestStore(test *testing.T) (*postgresOutboxStore, context.Context) {
	test.Helper()
	connection := os.Getenv("SPIKE_TEST_PG")
	if connection == "" {
		if os.Getenv("CI") == "true" {
			test.Fatal("SPIKE_TEST_PG is required in CI")
		}
		test.Skip("set SPIKE_TEST_PG to run the isolated PostgreSQL tests")
	}
	ctx, cancel := context.WithTimeout(test.Context(), 20*time.Second)
	test.Cleanup(cancel)
	pool, err := pgxpool.New(ctx, connection)
	if err != nil {
		test.Fatal(err)
	}
	schema := "spike_s4_" + strings.ToLower(rand.Text())
	test.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := pool.Exec(cleanup, "DROP SCHEMA IF EXISTS "+pgx.Identifier{schema}.Sanitize()+" CASCADE"); err != nil {
			test.Error(err)
		}
		pool.Close()
	})
	store, err := newPostgresOutboxStore(pool, schema, 10*time.Second)
	if err != nil {
		test.Fatal(err)
	}
	if err := store.initialize(ctx); err != nil {
		test.Fatal(err)
	}
	return store, ctx
}

func TestPostgresOutboxFencesExpiredAndSupersededLeases(test *testing.T) {
	store, ctx := postgresOutboxTestStore(test)
	first, acquired, err := store.acquireLease(ctx, "same-holder")
	if err != nil || !acquired || first.token != 1 {
		test.Fatalf("first=%+v acquired=%v error=%v", first, acquired, err)
	}
	if _, acquired, err := store.acquireLease(ctx, "other"); err != nil || acquired {
		test.Fatalf("unexpired lease taken over: acquired=%v error=%v", acquired, err)
	}
	var id int64
	if err := store.pool.QueryRow(ctx, fmt.Sprintf("INSERT INTO %s (partition_key, envelope) VALUES ($1, $2) RETURNING id", store.outbox),
		"ride-1", traceEvent("first", "ride", "ride-1", 0)).Scan(&id); err != nil {
		test.Fatal(err)
	}
	if _, err := store.pool.Exec(ctx, fmt.Sprintf("UPDATE %s SET expires_at = clock_timestamp() - interval '1 second'", store.leases)); err != nil {
		test.Fatal(err)
	}
	if renewed, err := store.maintainLease(ctx, first); err != nil || renewed {
		test.Fatalf("expired lease renewed: renewed=%v error=%v", renewed, err)
	}
	if marked, err := store.markPublished(ctx, first, []int64{id}); err != nil || marked {
		test.Fatalf("expired lease marked: marked=%v error=%v", marked, err)
	}
	second, acquired, err := store.acquireLease(ctx, "same-holder")
	if err != nil || !acquired || second.token != first.token+1 {
		test.Fatalf("second=%+v acquired=%v error=%v", second, acquired, err)
	}
	for _, stale := range []outboxRelayLease{first, {holder: "wrong-holder", token: second.token}} {
		if renewed, err := store.maintainLease(ctx, stale); err != nil || renewed {
			test.Fatalf("stale lease %+v renewed: renewed=%v error=%v", stale, renewed, err)
		}
		if released, err := store.releaseLease(ctx, stale); err != nil || released {
			test.Fatalf("stale lease %+v released successor: released=%v error=%v", stale, released, err)
		}
		if marked, err := store.markPublished(ctx, stale, []int64{id}); err != nil || marked {
			test.Fatalf("stale lease %+v marked: marked=%v error=%v", stale, marked, err)
		}
	}
	if pending, err := store.pending(ctx, 1); err != nil || len(pending) != 1 || pending[0].id != id {
		test.Fatalf("pending=%+v error=%v", pending, err)
	}
	if marked, err := store.markPublished(ctx, second, []int64{id}); err != nil || !marked {
		test.Fatalf("successor mark: marked=%v error=%v", marked, err)
	}
	if pending, err := store.pending(ctx, 1); err != nil || len(pending) != 0 {
		test.Fatalf("published row returned: pending=%+v error=%v", pending, err)
	}
}

func postgresOutboxTestRows(test *testing.T, ctx context.Context, store *postgresOutboxStore) []outboxRelayRow {
	test.Helper()
	for version := range 2 {
		if _, err := store.pool.Exec(ctx, fmt.Sprintf("INSERT INTO %s (partition_key, envelope) VALUES ($1, $2)", store.outbox),
			"ride-1", traceEvent(fmt.Sprintf("event-%d", version), "ride", "ride-1", int64(version))); err != nil {
			test.Fatal(err)
		}
	}
	rows, err := store.pending(ctx, 2)
	if err != nil || len(rows) != 2 {
		test.Fatalf("rows=%+v error=%v", rows, err)
	}
	return rows
}

func TestPostgresOutboxRollsBackIncompleteBatchMarks(test *testing.T) {
	store, ctx := postgresOutboxTestStore(test)
	rows := postgresOutboxTestRows(test, ctx, store)
	lease, acquired, err := store.acquireLease(ctx, "first")
	if err != nil || !acquired {
		test.Fatalf("acquired=%v error=%v", acquired, err)
	}
	for _, ids := range [][]int64{
		nil, {rows[0].id, rows[1].id + 100}, {rows[0].id, rows[0].id}, {-1, rows[1].id},
	} {
		if marked, err := store.markPublished(ctx, lease, ids); err == nil || marked {
			test.Fatalf("incomplete batch %v marked=%v error=%v", ids, marked, err)
		}
		if pending, err := store.pending(ctx, 2); err != nil || !reflect.DeepEqual(pending, rows) {
			test.Fatalf("partial mark was not rolled back: pending=%+v error=%v", pending, err)
		}
	}
	if marked, err := store.markPublished(ctx, lease, []int64{rows[0].id}); err != nil || !marked {
		test.Fatalf("first mark=%v error=%v", marked, err)
	}
	if marked, err := store.markPublished(ctx, lease, []int64{rows[0].id, rows[1].id}); err == nil || marked {
		test.Fatalf("already-published member accepted: marked=%v error=%v", marked, err)
	}
	if pending, err := store.pending(ctx, 2); err != nil || !reflect.DeepEqual(pending, rows[1:]) {
		test.Fatalf("unpublished member was lost: pending=%+v error=%v", pending, err)
	}
}

func TestPostgresOutboxMarkWaitsForTakeoverAndThenRejectsOldOwner(test *testing.T) {
	store, ctx := postgresOutboxTestStore(test)
	rows := postgresOutboxTestRows(test, ctx, store)
	first, acquired, err := store.acquireLease(ctx, "first")
	if err != nil || !acquired {
		test.Fatalf("acquired=%v error=%v", acquired, err)
	}
	takeover, err := store.pool.Begin(ctx)
	if err != nil {
		test.Fatal(err)
	}
	defer takeover.Rollback(ctx)
	var blockerPID int32
	if err := takeover.QueryRow(ctx, "SELECT pg_backend_pid()").Scan(&blockerPID); err != nil {
		test.Fatal(err)
	}
	if _, err := takeover.Exec(ctx, fmt.Sprintf(`UPDATE %s SET holder = 'second', token = token + 1,
		expires_at = clock_timestamp() + interval '10 seconds' WHERE name = 'outbox-relay'`, store.leases)); err != nil {
		test.Fatal(err)
	}
	result := make(chan error, 1)
	go func() {
		marked, err := store.markPublished(ctx, first, []int64{rows[0].id, rows[1].id})
		if marked {
			err = errors.New("old holder marked after takeover")
		}
		result <- err
	}()
	ticker := time.NewTicker(5 * time.Millisecond)
	defer ticker.Stop()
	waiting := false
	for !waiting {
		select {
		case err := <-result:
			test.Fatalf("mark did not serialize behind lease update: %v", err)
		case <-ctx.Done():
			test.Fatal(ctx.Err())
		case <-ticker.C:
			if err := store.pool.QueryRow(ctx, `SELECT EXISTS (
				SELECT 1 FROM pg_stat_activity WHERE $1 = ANY(pg_blocking_pids(pid)))`, blockerPID).Scan(&waiting); err != nil {
				test.Fatal(err)
			}
		}
	}
	if err := takeover.Commit(ctx); err != nil {
		test.Fatal(err)
	}
	select {
	case err := <-result:
		if err != nil {
			test.Fatal(err)
		}
	case <-ctx.Done():
		test.Fatal(ctx.Err())
	}
	if pending, err := store.pending(ctx, 2); err != nil || !reflect.DeepEqual(pending, rows) {
		test.Fatalf("stale mark changed rows: pending=%+v error=%v", pending, err)
	}
}

func TestPostgresOutboxRollsBackExpiryDuringMark(test *testing.T) {
	store, ctx := postgresOutboxTestStore(test)
	rows := postgresOutboxTestRows(test, ctx, store)
	lease, acquired, err := store.acquireLease(ctx, "first")
	if err != nil || !acquired {
		test.Fatalf("acquired=%v error=%v", acquired, err)
	}
	function := pgx.Identifier{store.schema, "expire_during_mark"}.Sanitize()
	if _, err := store.pool.Exec(ctx, fmt.Sprintf(`CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
		BEGIN
			UPDATE %s SET expires_at = clock_timestamp() - interval '1 second' WHERE name = 'outbox-relay';
			RETURN NEW;
		END $$`, function, store.leases)); err != nil {
		test.Fatal(err)
	}
	if _, err := store.pool.Exec(ctx, fmt.Sprintf(`CREATE TRIGGER expire_during_mark BEFORE UPDATE ON %s
		FOR EACH ROW EXECUTE FUNCTION %s()`, store.outbox, function)); err != nil {
		test.Fatal(err)
	}
	if marked, err := store.markPublished(ctx, lease, []int64{rows[0].id, rows[1].id}); err != nil || marked {
		test.Fatalf("expired while marking: marked=%v error=%v", marked, err)
	}
	if pending, err := store.pending(ctx, 2); err != nil || !reflect.DeepEqual(pending, rows) {
		test.Fatalf("expired mark was not rolled back: pending=%+v error=%v", pending, err)
	}
}

func TestPostgresOutboxRelayReplaysAfterLostAcknowledgement(test *testing.T) {
	store, ctx := postgresOutboxTestStore(test)
	rows := postgresOutboxTestRows(test, ctx, store)
	first, acquired, err := store.acquireLease(ctx, "first")
	if err != nil || !acquired {
		test.Fatalf("acquired=%v error=%v", acquired, err)
	}
	failure := errors.New("acknowledgement lost")
	injected := false
	var received strings.Builder
	relay := outboxRelay{store: store, lease: first, batchSize: 2,
		publish: func(_ context.Context, row outboxRelayRow) error {
			received.Write(row.envelope)
			received.WriteByte('\n')
			if row.id == rows[1].id && !injected {
				injected = true
				return failure
			}
			return nil
		},
	}
	if count, err := relay.relayBatch(ctx); count != 0 || !errors.Is(err, failure) {
		test.Fatalf("first batch count=%d error=%v", count, err)
	}
	if released, err := store.releaseLease(ctx, first); err != nil || !released {
		test.Fatalf("release=%v error=%v", released, err)
	}
	second, acquired, err := store.acquireLease(ctx, "second")
	if err != nil || !acquired || second.token != first.token+1 {
		test.Fatalf("second=%+v acquired=%v error=%v", second, acquired, err)
	}
	if renewed, err := store.maintainLease(ctx, second); err != nil || !renewed {
		test.Fatalf("renewed=%v error=%v", renewed, err)
	}
	relay.lease = second
	if count, err := relay.relayBatch(ctx); count != 2 || err != nil {
		test.Fatalf("successor batch count=%d error=%v", count, err)
	}
	if pending, err := store.pending(ctx, 2); err != nil || len(pending) != 0 {
		test.Fatalf("pending=%+v error=%v", pending, err)
	}
	var expected strings.Builder
	for _, row := range rows {
		expected.Write(row.envelope)
		expected.WriteByte('\n')
	}
	report, err := verifyOutboxTrace(strings.NewReader(expected.String()), strings.NewReader(received.String()))
	if err != nil || !report.ok() || report.Unique != 2 || report.Duplicates != 2 {
		test.Fatalf("trace report=%+v error=%v", report, err)
	}
}

func TestPostgresOutboxFindsLateCommitAndIgnoresRollback(test *testing.T) {
	store, ctx := postgresOutboxTestStore(test)
	lease, acquired, err := store.acquireLease(ctx, "first")
	if err != nil || !acquired {
		test.Fatalf("acquired=%v error=%v", acquired, err)
	}
	late, err := store.pool.Begin(ctx)
	if err != nil {
		test.Fatal(err)
	}
	defer late.Rollback(ctx)
	var lateID, earlyID int64
	insert := fmt.Sprintf("INSERT INTO %s (partition_key, envelope) VALUES ($1, $2) RETURNING id", store.outbox)
	if err := late.QueryRow(ctx, insert, "ride-late", traceEvent("late", "ride", "ride-late", 0)).Scan(&lateID); err != nil {
		test.Fatal(err)
	}
	if err := store.pool.QueryRow(ctx, insert, "ride-early", traceEvent("early", "ride", "ride-early", 0)).Scan(&earlyID); err != nil {
		test.Fatal(err)
	}
	if earlyID <= lateID {
		test.Fatalf("late commit must have a lower ID: late=%d early=%d", lateID, earlyID)
	}
	var delivered []int64
	relay := outboxRelay{store: store, lease: lease, batchSize: 1,
		publish: func(_ context.Context, row outboxRelayRow) error {
			delivered = append(delivered, row.id)
			return nil
		},
	}
	if count, err := relay.relayBatch(ctx); count != 1 || err != nil || !reflect.DeepEqual(delivered, []int64{earlyID}) {
		test.Fatalf("uncommitted event visible: count=%d delivered=%v error=%v", count, delivered, err)
	}
	if err := late.Commit(ctx); err != nil {
		test.Fatal(err)
	}
	if count, err := relay.relayBatch(ctx); count != 1 || err != nil || !reflect.DeepEqual(delivered, []int64{earlyID, lateID}) {
		test.Fatalf("late event skipped: count=%d delivered=%v error=%v", count, delivered, err)
	}
	aborted, err := store.pool.Begin(ctx)
	if err != nil {
		test.Fatal(err)
	}
	defer aborted.Rollback(ctx)
	if _, err := aborted.Exec(ctx, insert, "ride-aborted", traceEvent("aborted", "ride", "ride-aborted", 0)); err != nil {
		test.Fatal(err)
	}
	if err := aborted.Rollback(ctx); err != nil {
		test.Fatal(err)
	}
	if count, err := relay.relayBatch(ctx); count != 0 || err != nil || len(delivered) != 2 {
		test.Fatalf("rolled-back event delivered: count=%d delivered=%v error=%v", count, delivered, err)
	}
}
