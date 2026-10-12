# ADR-029: Bounded, city-owned computed surge

- **Status:** Accepted
- **Date:** 2026-10-12
- **Related:** [ADR-012](ADR-012-pricing-and-zones.md), [ADR-028](ADR-028-city-owned-database-work.md)

## Context

Computed surge is an opt-in phase-18 policy. Existing rule-based quotes remain the default. City ownership already provides fenced database-only transactions; live locations remain outside PostgreSQL and Kafka is not a prerequisite for pricing.

## Problem

Incomplete supply, repeated damping after retries, stale observations, or writes by an old city owner could produce misleading prices. Surge must be explainable and must not silently substitute zero supply for a failed measurement.

## Options considered

| Option | Benefits | Costs |
|---|---|---|
| Consume demand and supply from Kafka | Stream-oriented aggregation | Pricing depends on stream lag and replay correctness |
| Query ride internals and live positions | Direct measurements | Crosses module ownership boundaries |
| Pricing-owned demand, bounded live supply, fenced database publication | Reuses transactional booking evidence and existing stores | Bounded initial city size; no global atomic snapshot across stores |

## Decision

1. Demand uses quotes created in the last five minutes and quotes consumed by bookings in that interval. `used_at` is written in the booking transaction, so rollback and duplicate booking do not add demand. Counts include later-cancelled bookings and expired quotes: they represent requests, not successful matches. The stored pickup zone is used, without reinterpreting historical demand after a service-area edit.
2. Demand is rides plus 0.20 times quotes. Supply is fresh matching-eligible drivers in the zone plus 0.50 times drivers in the neighboring H3 ring. Special areas use their own local supply only and do not donate supply to an implicit H3 ring. These weights and special-area rules are initial assumptions, not outcome certification.
3. Read at most 10,000 positioned online drivers per city and reject overflow, not a truncated count. Resolve zones in one city-scoped PostGIS batch. Reads may observe concurrent driver changes; this is a bounded sampling policy, not a transactionally consistent cross-store snapshot. Measurement failures skip the update and preserve the old value until it expires.
4. Each enabled city has an explicit deployment-configured step table: inclusive ratio thresholds, a `0 -> 1.00` baseline, nondecreasing multipliers, and a 2.00 maximum. No guessed demand thresholds enable themselves. Snapshot the steps and raw/weighted counts in history. A separate editable `surge_steps` table is deferred until a versioned administration contract is needed.
5. Compare demand with threshold times `max(supply, 1)` using exact decimals; do not round a ratio into a higher step. Move at most 0.20 from the previous multiplier per committed update, including decreases. First publication starts from 1.00. These are assumed initial policy settings.
6. Sampling occurs before taking the city lease lock. A captured ownership claim authorizes the final database transaction. Database time rejects samples older than ten seconds or from the future. A persisted city schedule permits at most one update per sixty seconds across retries and ownership changes. Current values, history, and schedule commit together; the city guard rolls everything back if its lease expires.
7. Quotes in configured cities read fresh database values, with a two-minute lifetime measured from observation, not publication. Missing or stale values use the existing surge rules, then 1.00. Existing quotes remain immutable. The planned Valkey surge cache is deferred; the indexed database read is the initial authoritative path.
8. History is retained for seven days. Ownership, measurement, publication and quote-source metrics are separate. No new public HTTP contract is required for this opt-in deployment configuration.

## Trade-offs

- One city-wide policy spans categories. Category-specific supply and demand require a separate design.
- Deployment configuration must agree between API and dispatch processes; threshold changes require a deployment.
- Up to 10,000 zones are evaluated, including previously computed zones so prices can decrease when activity disappears. Overflow fails the cycle explicitly.
- A loss of the live index cannot be distinguished from genuinely empty supply by a single snapshot alone; the job must require an established, sufficiently old city epoch before publishing.

## Consequences

Tests must prove exact thresholds, bounded reads, city/special-area isolation, demand time windows, expiry, retry throttling, ownership fencing, atomic rollback, quote fallback and unchanged saved quotes. Capacity and improved matching outcomes remain separate phase exit criteria.

## Revisit when

- Measurements require larger cities, incremental supply aggregation, per-category policies, editable step tables or a read cache.
- Experiments justify changing the assumed weights, timing, damping or special-area policy.