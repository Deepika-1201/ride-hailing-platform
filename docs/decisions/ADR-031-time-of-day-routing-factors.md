# ADR-031: Time-of-day factors outside the raw route cache

- **Status:** Accepted
- **Date:** 2026-10-12
- **Related:** [ADR-013](ADR-013-routing-provider.md), [ADR-027](ADR-027-weighted-dispatch-scoring.md), [ADR-030](ADR-030-stable-ranking-experiments.md)

## Context

OSRM provides static road times. Phase 18 calls for optional zone/time-of-day factors while keeping the raw ten-minute route cache valid across time-window changes. The mock already has its own time-of-day profile.

## Problem

Caching adjusted results can retain peak-time estimates after a window ends. Applying one pickup-zone factor to an entire matrix ignores the different driver origins. Reapplying a factor to mock fallback also counts time-of-day behavior twice.

## Options considered

| Option | Benefits | Costs |
|---|---|---|
| Adjust before caching | Few operations per cache hit | Stale factors and a larger cache-key contract |
| Call a live traffic service | Potentially better estimates | Paid/external dependency and new failure modes |
| Apply explicit local-time windows after the raw cache | Reuses OSRM and existing zones | Static assumptions, not measured traffic |

## Decision

1. `ride.routing.traffic` is disabled by default. Enabled cities must also have OSRM enabled. Configuration supplies at most 100 cities with at most 100 windows each. A window has a zone, ISO weekday numbers, start/end local times and a duration multiplier in 1.00..3.00 with at most two decimal places **(assumed bounds)**.
2. Zones are H3 resolution-7 cells, `area:<code>` special areas, or `*` for the city's default. Area codes resolve through the existing active-area priority rules. An absent/inactive named area never matches. Overlapping applicable windows select the highest multiplier; they do not multiply together.
3. Use the origin city's time zone, not the caller's time-zone representation. Starts are inclusive and ends exclusive. A midnight-wrapping window belongs to the weekday on which it starts. Equal start/end times are rejected. Daylight-saving behavior follows local civil time, including repeated or skipped hours.
4. Only successful raw OSRM results are adjusted. Distance is unchanged. Route seconds round half-up after multiplication; matrix cells retain fractional seconds and unreachable cells remain unreachable. Mock and mock-fallback behavior is unchanged.
5. Adjustment happens after reading/writing the existing raw route cache. Adjusted results never enter it. Matrices remain uncached; one bounded city-scoped zone lookup identifies each origin before applying its row's factor. Origins outside the task city keep factor 1.
6. Quotes store source `OSRM_ADJUSTED` when a nonidentity factor is applied. ETA and weighted decisions carry `traffic_multiplier` with each scored candidate, preserving its origin association across sorting. Routing inputs can change with departure time even while an experiment's strategy and weights stay captured on the search task.
7. Arithmetic overflow rejects the whole adjustment and retains the unadjusted result. A matrix is never returned partly adjusted after overflow. Metrics expose this outcome. Geography/database errors continue through the existing caller failure handling rather than fabricating a zone.
8. `routing_traffic_total{kind,outcome}` uses `route|matrix` and `adjusted|unchanged|overflow`. Thresholds are deployment configuration, not an editable or independently versioned traffic feed. No city or raw-coordinate metric labels are added.

## Trade-offs

- An origin-based factor is an approximation; it does not model every road segment crossed or destination congestion.
- Changing configured windows requires consistent deployment across route-calculating roles. Quote provenance distinguishes adjusted times but does not retain the complete window configuration; dispatch retains the actual applied factor.
- Additional zone lookup time is outside the OSRM HTTP deadline and must be included in later latency/contention measurements.
- The feature remains opt-in for the earlier capacity baselines. Neither these factors nor their tests establish improved ETA accuracy.

## Consequences

Tests cover local-time boundaries, midnight wrapping, opt-in startup checks, cache reuse across windows, fallback isolation, matrix-origin factors, unreachable cells, overflow and quote/decision provenance. The existing reservation protocol and nearest-ranking default do not change.

## Revisit when

- Measured ETA errors justify segment-level factors, versioned rule administration, more provenance, or a live traffic source.