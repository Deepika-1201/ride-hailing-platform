# ADR-023: Recurring jobs scheduled by the expiry of a database lease

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [LLD](../low-level-design.md) §1.3, §5.5, §5.7; [ADR-001](ADR-001-architecture-style.md), [ADR-005](ADR-005-durable-timers.md)

## Context

- Some background work must run about once per interval across the whole cluster, not once per node: retention deletes, audit partition maintenance, and later the stuck-ride detector and the reconcilers' bookkeeping (LLD §5.7).
- Every node of a role is identical, and nodes come and go during rolling deploys and scaling. No node is configured as "the scheduler".
- Phase 2 already has leases with fencing tokens in `platform.leases`, judged by the database clock (LLD §1.4, §5.5).
- Spring's `@Scheduled` runs on every node and knows nothing about roles; an ArchUnit rule forbids it (LLD §1.3).

## Problem

How does a job run once per interval across the cluster, on a node with the right role, without a dedicated scheduler process and without trusting the nodes' clocks?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. A lease per job, taken for one interval and left to expire** | One statement per check; no renewals; the database clock alone spaces the runs; a crashed node blocks nothing for longer than one interval | A run that outlasts its interval may overlap the next; a failed run waits a full interval |
| B. A leader lease held continuously, the leader running every job on its own timer | Familiar leader election; failed runs can retry sooner | Renewal traffic and leader hand-over logic; the leader's clock schedules the runs; one node does all the work |
| C. A `scheduled_runs` table with the next due time per job, claimed with `SKIP LOCKED` | Exact schedule; supports cron expressions | Another table and claim protocol for a handful of hourly jobs; duplicates what timers already do |
| D. ShedLock or Quartz clustered mode | Off the shelf | A new dependency and its tables for what a single SQL statement does; Quartz brings its own threads and schema |

## Decision

1. Jobs implement `RecurringJob` (name, role, interval, run). Each node runs, per job of its roles, one virtual thread that checks every quarter interval (between 1 s and 1 min) whether it can acquire `job:<name>` with a TTL of one interval, and runs the job if so.
2. The lease is neither renewed nor released. Its expiry, one interval after the run started, makes the job due again.
3. Jobs are idempotent and do their own transactions, so an overlapping or repeated run is harmless.
4. Continuous loops that need exactly one active instance (the outbox relay) hold a renewed lease instead and fence their writes with its token (ADR-008, LLD §5.5).

## Trade-offs

- Runs drift: the next run starts up to a quarter interval (at most 1 min) after the lease expires.
- A failed run isn't retried until its interval has passed. For hourly maintenance that is acceptable; a job that needs quick retries should use a timer instead (ADR-005).
- Without renewal, a run longer than its interval can overlap the next one, so idempotence is a requirement, not an optimisation.

## Consequences

- No scheduler process and no extra table: `platform.leases` holds one row per job, and its `expires_at` shows when the job is next due.
- `ride.workers.autostart=false` stops all job loops, so tests call `runIfDue` directly and expire the lease to simulate the next interval.
- A new job is one bean; it runs only in processes with its role.

## Revisit when

- A job needs a wall-clock schedule (for example "at 02:00 Asia/Kolkata"), or runs that must never overlap.
- The number of jobs grows enough that one thread per job per node matters.
