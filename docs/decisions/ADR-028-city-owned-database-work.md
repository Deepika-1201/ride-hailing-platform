# ADR-028: City ownership fences singleton database work

- **Status:** Accepted
- **Date:** 2026-10-12
- **Related:** [ADR-005](ADR-005-durable-timers.md), [ADR-023](ADR-023-recurring-jobs.md), [LLD](../low-level-design.md) sections 5.5 and 19.2

## Context

Computed surge and the batch-matching experiment need one active dispatch owner per city. Sequential search attempts already coordinate with database row locks and conditional reservations and do not need a city leader. Pricing may use city ownership without depending on dispatch internals.

## Problem

An owner may pause, lose database connectivity, restart, or finish asynchronous preparation after another process takes over. Ownership discovery alone cannot authorize a later write.

## Options considered

| Option | Benefits | Costs |
|---|---|---|
| One global dispatch leader | Simple | Couples cities and prevents independent failover |
| Kafka partition ownership | Fits a stream-processing deployment | Adds broker dependence to matching and surge |
| Per-city PostgreSQL leases with guarded commits | Reuses existing fencing and database clock | Requires short database-only commit transactions |

## Decision

1. An opt-in, dispatch-only coordinator maintains `city-owner:<city>` leases for an explicit configured city set. Defaults are a ten-second lease and three-second renewal **(assumed initial timing)**; renewal must be less than half the TTL.
2. Expose claims through a platform API. A claim is a city, instance holder and fencing token. It is a snapshot, not proof that ownership remains valid.
3. A caller passes its captured claim when committing work. The guard owns the transaction, locks the lease row, checks holder/token and the current database clock after lock acquisition, executes database mutations, and checks expiry again before commit. Expiry rolls back the work. The lock remains held through commit, so a successor cannot take over until that transaction finishes.
4. Remote calls and expensive preparation occur outside the guard. A failure may leave prepared work unused; it must not create external effects under the mistaken assumption that a local claim is still valid.
5. Renewal errors discard local claims. Shutdown rejects new work, clears claims and attempts token-scoped release within a shared five-second cleanup budget; failed or unfinished releases expire naturally. A stopped owner's in-flight guarded database work rolls back when it observes the cleared claim. An acquisition completing after shutdown is released, not republished into local ownership. Restart does not reuse stale claims.
6. Sequential dispatch remains leaderless. Owning a city does not itself execute surge or matching jobs, and the coordinator is disabled by default until a city-owned job is explicitly enabled.

## Trade-offs

- First-acquirer ownership is not load balancing; one node may own multiple configured cities.
- The guard proves ordering of database commits and takeover, not exactly-once external side effects.
- Database failures can temporarily leave a city without an owner. Correctness takes priority over work continuing with uncertain authority.
- The bounded database connection and statement timeouts still apply. Long work inside the guard can delay takeover and must be avoided.

## Consequences

Tests cover independent cities, expiry before and during work, same-holder token changes, takeover, late acquisition, renewal failure and lifecycle restart. Ownership metrics are distinct from job success metrics. Surge and batch matching must use the captured claim when they add their commit paths.

## Revisit when

- Measurements require balanced ownership, a dynamic city registry or a different takeover budget.
- A city-owned operation needs external side effects or transactions too long for this guard.