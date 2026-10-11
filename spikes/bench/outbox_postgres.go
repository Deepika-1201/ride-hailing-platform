package main

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type postgresOutboxStore struct {
	pool   *pgxpool.Pool
	schema string
	outbox string
	leases string
	ttl    time.Duration
}

func newPostgresOutboxStore(pool *pgxpool.Pool, schema string, ttl time.Duration) (*postgresOutboxStore, error) {
	if pool == nil || !strings.HasPrefix(schema, "spike_s4_") || len(schema) <= len("spike_s4_") || len(schema) > 63 || ttl < time.Millisecond {
		return nil, errors.New("a pool, a spike_s4_* schema of at most 63 bytes and a positive millisecond lease TTL are required")
	}
	return &postgresOutboxStore{pool: pool, schema: schema, ttl: ttl,
		outbox: pgx.Identifier{schema, "outbox"}.Sanitize(),
		leases: pgx.Identifier{schema, "leases"}.Sanitize(),
	}, nil
}

func (store *postgresOutboxStore) initialize(ctx context.Context) error {
	transaction, err := store.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer transaction.Rollback(ctx)
	for _, statement := range []string{
		"CREATE SCHEMA IF NOT EXISTS " + pgx.Identifier{store.schema}.Sanitize(),
		fmt.Sprintf(`CREATE TABLE IF NOT EXISTS %s (
			id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
			partition_key text NOT NULL CHECK (partition_key <> ''),
			envelope jsonb NOT NULL,
			published_at timestamptz)`, store.outbox),
		fmt.Sprintf("CREATE INDEX IF NOT EXISTS outbox_pending ON %s (id) WHERE published_at IS NULL", store.outbox),
		fmt.Sprintf(`CREATE TABLE IF NOT EXISTS %s (
			name text PRIMARY KEY,
			holder text,
			token bigint NOT NULL CHECK (token >= 0),
			expires_at timestamptz,
			CHECK ((holder IS NULL) = (expires_at IS NULL)))`, store.leases),
		fmt.Sprintf("INSERT INTO %s (name, token) VALUES ('outbox-relay', 0) ON CONFLICT (name) DO NOTHING", store.leases),
	} {
		if _, err := transaction.Exec(ctx, statement); err != nil {
			return err
		}
	}
	return transaction.Commit(ctx)
}

func (store *postgresOutboxStore) lockedLease(ctx context.Context) (pgx.Tx, error) {
	transaction, err := store.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return nil, err
	}
	var name string
	if err := transaction.QueryRow(ctx, fmt.Sprintf("SELECT name FROM %s WHERE name = 'outbox-relay' FOR UPDATE", store.leases)).Scan(&name); err != nil {
		transaction.Rollback(ctx)
		return nil, err
	}
	return transaction, nil
}

func (store *postgresOutboxStore) acquireLease(ctx context.Context, holder string) (outboxRelayLease, bool, error) {
	if holder == "" {
		return outboxRelayLease{}, false, errors.New("a lease holder is required")
	}
	transaction, err := store.lockedLease(ctx)
	if err != nil {
		return outboxRelayLease{}, false, err
	}
	defer transaction.Rollback(ctx)
	lease := outboxRelayLease{holder: holder}
	err = transaction.QueryRow(ctx, fmt.Sprintf(`UPDATE %s
		SET holder = $1, token = token + 1, expires_at = clock_timestamp() + $2::bigint * interval '1 millisecond'
		WHERE name = 'outbox-relay' AND (holder IS NULL OR expires_at <= clock_timestamp())
		RETURNING token`, store.leases), holder, store.ttl.Milliseconds()).Scan(&lease.token)
	if errors.Is(err, pgx.ErrNoRows) {
		return outboxRelayLease{}, false, nil
	}
	if err != nil {
		return outboxRelayLease{}, false, err
	}
	if err := transaction.Commit(ctx); err != nil {
		return outboxRelayLease{}, false, err
	}
	return lease, true, nil
}

func (store *postgresOutboxStore) maintainLease(ctx context.Context, lease outboxRelayLease) (bool, error) {
	transaction, err := store.lockedLease(ctx)
	if err != nil {
		return false, err
	}
	defer transaction.Rollback(ctx)
	result, err := transaction.Exec(ctx, fmt.Sprintf(`UPDATE %s
		SET expires_at = clock_timestamp() + $3::bigint * interval '1 millisecond'
		WHERE name = 'outbox-relay' AND holder = $1 AND token = $2 AND expires_at > clock_timestamp()`, store.leases),
		lease.holder, lease.token, store.ttl.Milliseconds())
	if err != nil || result.RowsAffected() != 1 {
		return false, err
	}
	err = transaction.Commit(ctx)
	return err == nil, err
}

func (store *postgresOutboxStore) releaseLease(ctx context.Context, lease outboxRelayLease) (bool, error) {
	transaction, err := store.lockedLease(ctx)
	if err != nil {
		return false, err
	}
	defer transaction.Rollback(ctx)
	result, err := transaction.Exec(ctx, fmt.Sprintf(`UPDATE %s SET holder = NULL, expires_at = NULL
		WHERE name = 'outbox-relay' AND holder = $1 AND token = $2`, store.leases), lease.holder, lease.token)
	if err != nil || result.RowsAffected() != 1 {
		return false, err
	}
	err = transaction.Commit(ctx)
	return err == nil, err
}

func (store *postgresOutboxStore) pending(ctx context.Context, limit int) ([]outboxRelayRow, error) {
	if limit < 1 {
		return nil, errors.New("a positive batch limit is required")
	}
	rows, err := store.pool.Query(ctx, fmt.Sprintf(`SELECT id, partition_key, envelope FROM %s
		WHERE published_at IS NULL ORDER BY id LIMIT $1`, store.outbox), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var pending []outboxRelayRow
	for rows.Next() {
		var row outboxRelayRow
		if err := rows.Scan(&row.id, &row.partitionKey, &row.envelope); err != nil {
			return nil, err
		}
		pending = append(pending, row)
	}
	return pending, rows.Err()
}

func (store *postgresOutboxStore) markPublished(ctx context.Context, lease outboxRelayLease, ids []int64) (bool, error) {
	if len(ids) == 0 {
		return false, errors.New("a nonempty batch is required")
	}
	transaction, err := store.lockedLease(ctx)
	if err != nil {
		return false, err
	}
	defer transaction.Rollback(ctx)
	result, err := transaction.Exec(ctx, fmt.Sprintf(`UPDATE %s SET published_at = clock_timestamp()
		WHERE id = ANY($1::bigint[]) AND published_at IS NULL
		AND EXISTS (SELECT 1 FROM %s WHERE name = 'outbox-relay'
			AND holder = $2 AND token = $3 AND expires_at > clock_timestamp())`, store.outbox, store.leases),
		ids, lease.holder, lease.token)
	if err != nil {
		return false, err
	}
	var owned bool
	err = transaction.QueryRow(ctx, fmt.Sprintf(`SELECT EXISTS (SELECT 1 FROM %s
		WHERE name = 'outbox-relay' AND holder = $1 AND token = $2 AND expires_at > clock_timestamp())`, store.leases),
		lease.holder, lease.token).Scan(&owned)
	if err != nil || !owned {
		return false, err
	}
	if result.RowsAffected() != int64(len(ids)) {
		return false, errors.New("batch does not match the pending events")
	}
	err = transaction.Commit(ctx)
	return err == nil, err
}
