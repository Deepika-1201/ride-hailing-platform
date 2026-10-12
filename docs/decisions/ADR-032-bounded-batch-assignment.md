# ADR-032: Bounded batch assignment before live offer integration

- **Status:** Accepted
- **Date:** 2026-10-12
- **Related:** [ADR-011](ADR-011-dispatch-protocol.md), [ADR-028](ADR-028-city-owned-database-work.md), [Dispatch design](../dispatch-design.md#8-batch-matching-v4-experiment)

## Context

Phase 18 includes a batch-matching experiment. The adoption gate needs measured hotspot outcomes, not an assumption that minimizing one matrix will improve the running system. The current dispatch path already protects reservation and ride-state invariants.

## Problem

We need a correct assignment engine and reproducible comparison inputs before changing how live search tasks produce offers. Rectangular matrices, unreachable pairs and scarce supply must not create duplicate assignments or trade away feasible matches simply to reduce total ETA.

## Options considered

| Option | Benefits | Costs |
|---|---|---|
| Hand-written Hungarian solver | Full control | More algorithmic correctness and maintenance risk |
| General constraint solver | Flexible future objectives | Unneeded complexity and dependency weight for the first experiment |
| JGraphT Hungarian implementation | Established algorithm library and bounded problem size | Still needs domain-specific feasibility mapping and independent verification |

## Decision

1. Pin `org.jgrapht:jgrapht-core:1.5.3` and use its Hungarian implementation for at most 200 drivers and 200 rides. This first slice is an offline engine and harness; it does not register a live offer policy or change search-task routing.
2. Matrix rows are driver origins and columns are ride pickups. A missing cell is unreachable; finite nonnegative values are pickup seconds, bounded by the application's integer duration ceiling. Keep original fractional values in returned matches.
3. Pad to a square graph. Dummy ride columns cost zero. Unreachable edges and dummy driver rows cost `(maximum_finite_eta + 1) * (square_size + 1)`, more than the total of any fully finite assignment. Minimize unmatched rides first, then total ETA. Never expose dummy or unreachable edges as matches.
4. Return matches in ride-index order. The caller owns matrix ordering; repeatability tests cover the pinned implementation. No business meaning or rider-priority guarantee is assigned to equal-cost alternatives yet.
5. Compare the same matrix with a sequential lowest-ETA baseline in input ride order, with driver index breaking equal-ETA ties. This is an offline ETA baseline, not a replay of the live nearest-distance or weighted policy and not a simulation of reservations or declines.
6. The harness accepts a bounded JSON input and records its SHA-256 digest, assignments, match rates, totals, means and single-run elapsed times. Compare percentage mean reduction only when both plans matched the same ride set and the baseline mean is positive. Output explicitly says it is not adoption evidence.
7. Reject oversized matrices instead of silently truncating. The planned larger-window greedy-with-regret path is deferred, along with bounded matrix preparation, per-city task routing, fenced transactional offer creation and simulator outcome measurement. The current OSRM adapter's 100 combined coordinates also require deliberate batching before larger live matrices can be prepared.

## Trade-offs

- The solver uses double-precision costs. Tests compare small generated problems with an exhaustive oracle, including fractional inputs, and exercise the full 200-by-200 limit; this is not arbitrary-precision optimization.
- One graph allocation per window is acceptable for the bounded experiment but still needs representative performance measurements.
- Maximizing count before ETA does not by itself implement age or reassignment priority under excess demand. That choice must be resolved before live integration.

## Consequences

The engine can be evaluated without starting PostgreSQL, Valkey, Kafka or the application. Synthetic improvements prove only that the comparison can distinguish strategies. The phase remains incomplete until live integration preserves existing invariants and hotspot measurements meet or reject the adoption threshold.

## Revisit when

- Real matrices require larger windows, weighted objectives, rider priority constraints or stronger numerical guarantees.
- Profiling identifies solver cost rather than matrix acquisition or reservations as the limiting factor.

## Implementation follow-up: 2026-10-13

The bounded matrix-preparation helper is now implemented and tested separately from live dispatch. It splits up
to 200 origins and 200 destinations into 50-by-50 tiles, keeps the caller's axis IDs and per-ride eligibility,
and rejects partial matrices, invalid dimensions/durations and traffic-factor changes across tiles.

An explicit positive budget of at most five seconds covers result acceptance, with monotonic checks before and
after routing calls and final assembly. It does not preempt a synchronous routing call; the provider retains its
own request timeout. Interrupted work returns no matrix without clearing the interrupt flag. The full 16-tile
shape, fractional values, unreachable pairs, failure paths and final-assembly expiry have focused tests.

This completes an additional prototype component, not the planned live policy. Task ownership, priority, sampling
freshness, atomic reservation/offer creation and simulator adoption evidence remain open. The separate
[portfolio release checklist](../portfolio-completion.md) keeps this work experimental and does not change the
original phase-18 exit criteria.