# ADR-027: Explainable weighted dispatch scoring

- **Status:** Accepted
- **Date:** 2026-10-12
- **Related:** [Dispatch design](../dispatch-design.md) section 7; [LLD](../low-level-design.md) section 19.2; [ADR-013](ADR-013-routing-provider.md)

## Context

The ETA ranker already obtains one bounded road-time matrix and preserves conditional driver reservation. Driver offer outcomes exist in `dispatch.driver_stats`, and availability records when a driver last entered their current state. Weighted ranking adds acceptance, idle time and heading without changing eligibility or default dispatch policy.

## Problem

The weighted result must be reproducible from recorded inputs, avoid extreme preference for a new driver with no history, and handle missing or low-speed heading data consistently.

## Options considered

| Option | Benefits | Costs |
|---|---|---|
| Continue ETA-only ranking | Simple and already proven | Cannot evaluate the additional signals |
| Unbounded raw weighted features | Few rules | Idle time can dominate; sparse history and missing motion distort scores |
| Bounded, smoothed, explicit components | Explainable comparisons and controlled experiments | Initial coefficients need measurement |

## Decision

1. Keep `nearest` as the default. `weighted` is an explicit city/category setting alongside `eta`.
2. Use the same road-ETA candidate set and reachability rules as `eta`. When road times are unavailable, preserve the existing distance fallback rather than mixing metres into a seconds-based score.
3. Score is `w_eta * eta_seconds + w_acceptance * (1 - acceptance_rate) - w_idle * idle_minutes + w_heading * heading_penalty`. Lower wins. Record the inputs, coefficients and component contributions in the decision log.
4. Acceptance is `(accepted + 8) / (accepted + declined + expired + 10)`. Withdrawn or still-pending offers are not counted as driver refusals. The 8/10 prior is an **assumed initial policy**, not a measured acceptance prediction.
5. Idle time means continuous time in `AVAILABLE`, measured from `status_changed_at` with the database clock and clamped to 0..30 minutes. A released offer restarts that interval. Thirty minutes is an **assumed initial cap**.
6. Heading penalty is `(1 - cos(heading - bearing_to_pickup)) / 2`, in 0..1. Missing, invalid or slower-than-1m/s motion uses neutral 0.5; at the pickup it is zero. These are **assumed initial policies**. Heading and speed must come from the same usable position as the candidate.
7. Initial weights are ETA 1, acceptance 60, idle 2, heading 30, expressed as contributions to a seconds-equivalent score. Store configurable weights with the existing city/category settings rather than introducing another administration endpoint. Bounds prevent nonfinite or extreme input; choosing coefficients remains an explicit operations decision.
8. Statistics are fetched in one bounded batch for considered candidates. Database failure aborts the search transaction; it is not swallowed inside a failed transaction. Availability is still checked by the existing conditional reservation after ranking.

## Trade-offs

- The outcome counters are lifetime observations, not a recency-weighted behavioral model.
- Idle credit rewards continuous availability, not time since the driver's last completed trip.
- Heading is device-supplied and cannot be treated as a guarantee that the driver will continue in that direction.
- Defaults are experiment starting points, not a claim of improved pickup time or fairness.

## Consequences

Tests must isolate every score contribution, prior, cap and missing-data rule, and then verify the selected driver and recorded score through real search transactions. Nearest and ETA behavior must remain unchanged. Performance and outcome comparisons are still required before adopting weighted ranking as a default.

## Revisit when

- Simulator or operational evidence supports different coefficients, recency weighting, signal bounds or idle semantics.
- A controlled comparison demonstrates sufficient benefit to propose changing the default policy.