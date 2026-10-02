# S-2: Timers in PostgreSQL

**Question:** can a PostgreSQL table hold the dispatch timers (offer timeouts, search timeouts, waiting at pickup) and fire them within ~1 s at cloud-tier rates without loading the database? ([requirements §12](../../docs/requirements.md#12-open-questions-for-design-spikes))

**Answer:** yes, with a wide margin. Polling every 250 ms, timers fired at most 0.3 s late at 10× the cloud-tier rate, using 0.26 CPU cores. After every poller stopped for 10 s, the ~6,000 overdue timers were cleared within 0.1 s of polling resuming. Decision: [ADR-005](../../docs/decisions/ADR-005-durable-timers.md).

## Setup

- Table `timers (id, kind, ref_id, due_at)` with a B-tree index on `due_at`; logged, synchronous commit (durable, as the product needs).
- Producers create timers due 15 s later (an offer timeout). 70% are cancelled 3–12 s after creation (the driver answered) with `DELETE … WHERE id = ?`.
- Two pollers, standing in for two `dispatch` replicas, claim due timers with:

  ```sql
  WITH due AS (
    SELECT id FROM timers WHERE due_at <= clock_timestamp()
    ORDER BY due_at LIMIT 200
    FOR UPDATE SKIP LOCKED)
  DELETE FROM timers t USING due WHERE t.id = due.id
  RETURNING clock_timestamp() - t.due_at   -- lag, on the database clock
  ```

  A poller asks again at once if it got a full batch, and otherwise sleeps for the poll interval. This is the Job Scheduler's indexed-polling pattern.
- Rates: about 200 timers/s at the cloud tier (offers plus other waits), 2,000/s for the designed-for tier. 20 s warm-up, 60 s measured. PostgreSQL 18 in the same 4-vCPU VM as S-1.

## Results

| Run | Created/s | Fired/s | Poll every | Fire lag p50 / p99 / max | Database CPU |
|---|---|---|---|---|---|
| Cloud tier | 200 | 60 | 250 ms | 125 / 250 / 252 ms | 0.07 cores |
| 10× cloud | 2,000 | 598 | 250 ms | 130 / 257 / 293 ms | 0.26 cores |
| 10× cloud | 2,000 | 598 | 100 ms | 56 / 109 / 183 ms | 0.32 cores |
| 10× cloud, all pollers stopped for 10 s mid-run | 2,000 | 598 | 250 ms | 155 ms / 9.5 s / 10.1 s | 0.26 cores |

Statement costs at 10× cloud: create 0.016 ms, cancel 0.010 ms, claiming a batch of up to 200 due timers 4.7 ms.

## What the numbers say

1. **The poll interval is the precision dial.** Lag is the interval plus a few milliseconds. Going from 250 ms to 100 ms halved the lag for 0.06 more cores at 10× the cloud rate; 250 ms is already four times better than the 1 s target.
2. **No leader and no failover step.** Pollers share the work through `SKIP LOCKED`, and a timer can be claimed only once because claiming deletes the row in the same statement.
3. **Recovery is immediate once any poller runs.** The 10 s outage left ~6,000 timers overdue; they were all claimed 59 ms after polling resumed, each late by roughly the outage length. Recovery time after a crash (NFR-7) is therefore the time until a surviving or restarted poller runs, not database work. With two or more `dispatch` replicas, losing one costs nothing.
4. **The table stays small.** About 20,000 pending timers at the designed-for rate; churn is inserts and deletes on a narrow table, which autovacuum handles.

## Limits of this spike

- The handler work (expiring the offer, offering the next driver) isn't included; in the product it runs inside the claiming transaction and adds a few milliseconds per timer.
- One PostgreSQL instance, two pollers, the load generator on the same VM.

Raw output: [raw/](raw/) (`s2-*.txt`).
