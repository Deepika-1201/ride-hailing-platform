# ADR-005: Durable timers in a PostgreSQL table, claimed with SKIP LOCKED

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [Requirements](../requirements.md) §5, FR-DS3, FR-DS5, NFR-7; [ADR-001](ADR-001-architecture-style.md), [ADR-003](ADR-003-postgresql-postgis.md); [spike S-2](../../spikes/results/s2-timers.md)

## Context

- Many waits in the ride lifecycle are timers: offer timeout (15 s), search timeout (3 min), waiting at pickup (5 min), the free-cancellation window (2 min), and driver auto-offline (10 min).
- A timer must fire even if the process that created it crashes (NFR-7), within about 1 s of its due time.
- Rates: ~200 timers created/s at the cloud tier, ~2,000/s designed-for; most offer timers are cancelled because the driver answers first.
- Phase 1 chose to build the timer mechanics rather than adopt a workflow engine (Q9), and to keep the Job Scheduler out of the runtime path (C-2). The Job Scheduler's indexed polling with `SKIP LOCKED` met a 1 s target there.

## Problem

Where do timers live, and how are they fired exactly when due, despite crashes, by several `dispatch` replicas?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. Timer table in PostgreSQL, claimed with `FOR UPDATE SKIP LOCKED`** | Durable with the rest of the state; created and cancelled in the same transaction as the change that starts or ends the wait; no leader. S-2: p99 ≤ 257 ms at 10× the cloud rate, 0.26 cores | Precision equals the poll interval; a small constant polling load |
| B. In-memory timing wheel per `dispatch` node | Millisecond precision; no database load | Lost on a crash unless rebuilt; needs ownership of timers per node |
| C. Valkey sorted set scored by due time | Fast | Valkey holds only rebuildable data (ADR-004); would become a second source of truth |
| D. Broker delayed delivery | No polling code | Kafka has no delayed messages; SQS caps delays at 15 min and can't cancel or change them |
| E. Temporal or Step Functions | Durable timers out of the box | Hides the mechanics the project means to teach (Q9); heavy for 15 s waits |
| F. The Job Scheduler project | Reuses portfolio work | A runtime dependency on another system for a 15 s timeout (C-2) |

## Decision

1. **One `timers` table** with a kind, the target aggregate's ID and expected version, and `due_at`, indexed on `due_at`. The module that owns the state registers the handler for each kind (offer timeout → Dispatch, pickup wait → Ride, auto-offline → Dispatch).
2. **Created and cancelled transactionally.** The transaction that starts a wait inserts its timer; the transaction that ends the wait early deletes it.
3. **Pollers in the `dispatch` role** claim due timers with `DELETE … RETURNING` over `SELECT … WHERE due_at <= clock_timestamp() ORDER BY due_at LIMIT 200 FOR UPDATE SKIP LOCKED`, every 250 ms, and again at once when a batch is full. The database clock decides what is due.
4. **The handler runs in the claiming transaction.** If it fails, the transaction rolls back and the timer stays due for the next poll.
5. **Handlers check state before acting**, for example "the offer is still pending at the expected version". Firing is therefore at least once, and a repeat does nothing.
6. Aggressive autovacuum settings for this table, which churns by design.

## Trade-offs

- Timers fire up to the poll interval late (~250 ms). That is irrelevant for waits of 15 s or more, and the interval can drop to 100 ms for 0.06 more cores at 10× the cloud rate.
- Polling adds a small constant query load per `dispatch` replica.
- A slow handler holds its claimed rows for the length of the transaction; handlers must stay short and do no network calls inside it.

## Consequences

- No leader election and no failover step: any number of `dispatch` replicas can poll, and losing one costs nothing.
- After a crash, overdue timers fire as soon as any poller runs; S-2 cleared a 10 s backlog of ~6,000 timers in 59 ms.
- Offers expire at most about 0.3 s late, so the 15 s offer window stays effectively exact.

## Revisit when

- A requirement needs precision tighter than ~250 ms.
- Timer rates grow far beyond the designed-for tier (~2,000/s).
- Batch matching in V4 moves dispatch to per-city owners, where in-memory scheduling per city (option B) becomes natural.
