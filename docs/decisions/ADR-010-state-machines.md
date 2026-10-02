# ADR-010: Explicit state machines with versioned conditional updates

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [Ride lifecycle](../ride-lifecycle.md), [dispatch design](../dispatch-design.md) §2, §5; [ADR-001](ADR-001-architecture-style.md), [ADR-008](ADR-008-outbox-and-events.md), [ADR-009](ADR-009-idempotency-and-identifiers.md)

## Context

- Rides, offers, driver availability, charges and refunds each move through a lifecycle that several actors change concurrently: riders, drivers, timers, operations and the payment provider.
- Illegal moves (a trip starting after a cancellation, a second driver on a ride) must be impossible, not just unlikely (NFR-1).
- The brief's state machine put payment states inside the ride, and Phase 1 pushed back. The Payment Orchestrator separated payment, attempt and refund state machines for the same reason.

## Problem

How are lifecycles modelled and enforced, and how do concurrent changes to the same record behave?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Status columns with ad-hoc checks | Quick | Rules scattered; races between read and write |
| **B. Hand-built transition tables plus versioned conditional updates** | Rules in one place per machine; the database rejects stale writes; same approach as the Payment Orchestrator | Some boilerplate per machine |
| C. Spring Statemachine | Library support | Heavy for a few machines; persistence and concurrency still hand-built |
| D. Event sourcing | Full history by design | Much more machinery (projections, snapshots, schema upcasting) than this project needs; the transition logs and outbox already give history |

## Decision

1. **Separate machines:** ride, offer, driver availability, charge and refund. Payment status is never a ride state.
2. **Each machine is an enum with a transition table in code.** For every allowed `(from, command) → to` the table lists the actor allowed, the preconditions and the events emitted. Anything else is `409 INVALID_TRANSITION`.
3. **Optimistic concurrency.** Every aggregate has a `version`. A transition is `UPDATE … SET status = :to, version = version + 1 … WHERE id = :id AND status = :from AND version = :v`. Zero rows changed means someone else moved first: reload and re-evaluate (natural idempotency, a conflict, or a retry).
4. **One transaction per transition** holds the state change, a transition-log row, outbox events, timer changes, the audit entry and the idempotency record. When one transition touches several aggregates (assignment touches ride, offer and availability), rows are locked in a fixed order: ride → offer → driver availability → timers.
5. **Database backstops** for the invariants: partial unique indexes on active rides per driver and per rider, and on pending offers per driver and per ride (ADR-003).

## Trade-offs

- Transition tables and conditional updates are written by hand for five machines.
- Optimistic concurrency means occasional reload-and-retry under contention, which is cheap because contention is per ride or per driver.

## Consequences

- Every transition is visible in one table, testable as a unit, and enforced by the database under concurrency.
- Transition logs give each ride a complete history for the operations timeline (FR-O1) without event sourcing.
- The concurrency tests in the testing strategy assert the invariants after hundreds of parallel conflicting commands.

## Revisit when

- Auditors or analytics need to replay full state history; event sourcing for one aggregate would then be worth evaluating.

## Amendments

- **2026-10-02, low-level design** ([LLD §6](../low-level-design.md#6-concurrency-rules), [review](../architecture-review.md) R-01, R-03): the lock order is ride → driver → offer → driver availability. Timers and search tasks have no place in it. Their pollers claim them first, and every other transaction removes them without waiting (`SKIP LOCKED`), leaving a row that is being handled to its handler, which re-checks state. Giving timers the last place, as decision 4 did, would let a timer handler and an acceptance deadlock.
