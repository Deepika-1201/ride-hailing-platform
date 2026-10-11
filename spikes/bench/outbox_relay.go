package main

import (
	"context"
	"errors"
	"fmt"
)

var errOutboxLeaseLost = errors.New("outbox relay lease lost")

type outboxRelayLease struct {
	holder string
	token  int64
}

type outboxRelayRow struct {
	id           int64
	partitionKey string
	envelope     []byte
}

type outboxRelayStore interface {
	maintainLease(context.Context, outboxRelayLease) (bool, error)
	pending(context.Context, int) ([]outboxRelayRow, error)
	markPublished(context.Context, outboxRelayLease, []int64) (bool, error)
}

type outboxRelay struct {
	store     outboxRelayStore
	publish   func(context.Context, outboxRelayRow) error
	lease     outboxRelayLease
	batchSize int
}

func (relay outboxRelay) checkLease(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	owned, err := relay.store.maintainLease(ctx, relay.lease)
	if err != nil {
		return fmt.Errorf("maintain lease: %w", err)
	}
	if !owned {
		return errOutboxLeaseLost
	}
	return nil
}

func (relay outboxRelay) relayBatch(ctx context.Context) (int, error) {
	if relay.batchSize < 1 || relay.lease.holder == "" || relay.lease.token < 1 {
		return 0, errors.New("a positive batch size and a lease holder/token are required")
	}
	if err := relay.checkLease(ctx); err != nil {
		return 0, err
	}
	rows, err := relay.store.pending(ctx, relay.batchSize)
	if err != nil {
		return 0, fmt.Errorf("read pending events: %w", err)
	}
	if len(rows) > relay.batchSize {
		return 0, errors.New("store exceeded the requested batch size")
	}
	ids := make([]int64, len(rows))
	for index, row := range rows {
		if row.id < 1 || (index > 0 && row.id <= rows[index-1].id) || row.partitionKey == "" || len(row.envelope) == 0 {
			return 0, errors.New("pending events must have increasing positive IDs, partition keys and envelopes")
		}
		ids[index] = row.id
	}
	for _, row := range rows {
		if err := relay.checkLease(ctx); err != nil {
			return 0, err
		}
		if err := relay.publish(ctx, row); err != nil {
			return 0, fmt.Errorf("publish row %d: %w", row.id, err)
		}
	}
	if len(rows) == 0 {
		return 0, nil
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	marked, err := relay.store.markPublished(ctx, relay.lease, ids)
	if err != nil {
		return 0, fmt.Errorf("mark published: %w", err)
	}
	if !marked {
		return 0, errOutboxLeaseLost
	}
	return len(rows), nil
}
