# ADR-011: Dispatch through search tasks in PostgreSQL, with sequential offers reserved at offer time

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [Dispatch design](../dispatch-design.md); [ADR-004](ADR-004-live-location-index.md), [ADR-005](ADR-005-durable-timers.md), [ADR-010](ADR-010-state-machines.md); requirements FR-DS1–FR-DS6, NFR-1, NFR-4, NFR-7, Q5

## Context

- Each booking needs a driver: find nearby available drivers, pick one, offer, and handle accept, decline, expiry, cancellation and crashes.
- NFR-7 requires booking to work while the broker is down, so dispatch can't be triggered only by Kafka events.
- Phase 1 chose sequential offers (Q5). It also pushed back on the brief's flow, which reserved the driver *after* acceptance: that lets a driver see two rides at once and accept both.
- Spike S-2 showed that claiming due rows with `SKIP LOCKED` is fast, precise and leaderless.

## Problem

1. What triggers and drives dispatch work?
2. When is a driver reserved, and how are offers sequenced?
3. How does the matching strategy stay replaceable?

## Options considered

### Trigger

| Option | Pros | Cons |
|---|---|---|
| **A. Search task rows in PostgreSQL, claimed with `SKIP LOCKED`** | Created in the booking transaction; survives crashes; no broker; any number of dispatch nodes | Polling (≤ 250 ms) |
| B. Kafka `RideRequested` events | Push-based | Booking → dispatch stops when Kafka is down (breaks NFR-7); retries and backoff need extra machinery |
| C. In-memory queue per node | Fastest | Lost on crash |

### Offers

| Option | Pros | Cons |
|---|---|---|
| **A. Sequential, reservation at offer time** | A driver sees one offer at a time; acceptance only confirms; simple and fair | Slow when drivers decline: each refusal can cost up to 15 s |
| B. Parallel offers to the top N | Fast | Reserves N drivers for one ride, or creates "already taken" races |
| C. Reservation after acceptance (the brief's flow) | Drivers aren't blocked while deciding | A driver can accept two rides; one acceptance must be revoked after the fact |
| D. Batch matching | Better global matches in dense zones | Needs city ownership and a 1–2 s window; unproven benefit (V4 experiment) |

## Decision

1. **Search tasks** in `dispatch.search_tasks`, one per searching ride. They are created in the booking or reassignment transaction and claimed by every `dispatch` node every 250 ms with `FOR UPDATE SKIP LOCKED`, ordered by priority and then age.
2. **One attempt per claim**, in one transaction:
   - candidates from the Valkey live index;
   - exclude drivers already offered this ride;
   - rank;
   - reserve the first candidate whose `AVAILABLE → OFFERED` conditional update changes a row (at most 5 tries);
   - create the offer, its 15 s timer and the decision record.
3. **Search schedule:**
   - no driver reserved: retry after 5 s (1 s when the attempt lost reservation races);
   - radius 2 km, +1 km per retry, up to 6 km;
   - a separate 3-min search timer ends the ride as `DRIVER_NOT_FOUND`.
4. **Sequential offers with reservation at offer time.** At most one pending offer per ride and per driver, backed by partial unique indexes. Accepting confirms an existing reservation in the ride's assignment transaction (ADR-001).
5. **Strategy interfaces:**
   - `CandidateRanker`: V1 nearest by straight-line distance, V4 ETA and weighted score;
   - `OfferPolicy`: V1 sequential, V4 batch-matching experiment.
6. **Decision log:** one row per attempt (FR-DS6), naming the strategy and its version.

## Trade-offs

- Dispatch work waits up to 250 ms for a poll, which is well inside the 2 s first-offer target.
- Sequential offers make declines expensive. Ranking on acceptance rate and ETA (V4) reduces declines, and batch matching may help hotspots.
- A reserved driver can't be offered other rides for up to 15 s while deciding.

## Consequences

- The core path (booking → offer → assignment) needs only PostgreSQL and Valkey.
- Any dispatch node can die mid-attempt; the transaction rolls back and another node continues.
- The brief's race scenarios 1–3 are impossible by construction rather than handled after the fact.

## Revisit when

- The V4 simulator shows batch matching or parallel offers beating sequential offers by a margin worth the complexity.
- Declines or expiries make up a large share of offers, suggesting the protocol, not the ranking, is the bottleneck.
