# Offline batch-matching experiment

This harness compares sequential lowest-ETA assignment with a bounded Hungarian solver on the same matrix.
It does not create offers, contact routing services, run a simulator or justify enabling batch matching.

From the repository root, with JDK 25:

```bash
./gradlew batchMatchingExperiment
./gradlew batchMatchingExperiment \
  -PbatchMatrix=/path/to/matrix.json \
  -PbatchReport=build/spikes/batch-matching/my-report.json
```

The default uses [example.json](example.json) and writes `build/spikes/batch-matching/report.json`.
The synthetic example has two feasible matches under both strategies: total pickup ETA is 101 seconds for
sequential assignment and 4 seconds for joint assignment. These deliberately illustrative values are not a
real-world improvement claim.

## Input

```json
{
  "name": "my-recorded-matrix",
  "durations_s": [[1, 2], [2, null]]
}
```

- Rows are drivers, columns are rides in the sequential baseline's processing order.
- Durations are finite nonnegative seconds, at most 2,147,483,647. `null` means unreachable.
- Input must be a nonempty rectangular matrix, at most 200 rows and 200 columns, in a file no larger than 2 MiB.
- Use the same eligibility exclusions, radius limits, prior-offer exclusions and traffic-adjusted times for both
  strategies when preparing representative input. The harness does not derive those from application state.

## Matrix preparation prototype

The separately tested `BatchMatrixPreparer` can assemble up to 200 driver origins and 200 ride pickups through
the existing routing provider. It is not registered as a background job, and this offline JSON command does not
invoke it or create offers.

- Requests use at most 50 origins and 50 destinations per tile, respecting OSRM's 100-coordinate limit. A full
  200-by-200 input requires 16 requests, preserving the caller's driver and ride IDs in their original order.
- The caller supplies each ride's eligible driver IDs. Ineligible and unreachable pairs remain absent even if a
  routing response contains a duration. Duplicate axis IDs and eligibility references outside the supplied driver
  pool are rejected.
- Missing/malformed tiles, out-of-range durations, changed per-origin traffic factors and interrupted work yield
  no prepared matrix, never a partially filled result. An omitted traffic factor is normalized to 1.00.
- A positive monotonic budget of at most five seconds is checked before and after calls and after final assembly.
  It is a result-acceptance budget, not a way to forcibly interrupt a synchronous provider: an in-flight call is
  still bounded by that provider's own timeout. Late results are discarded, and interruption preserves the flag.

Live task selection, priority semantics, snapshot freshness, city-ownership commit validation, reservations and
offers remain future work under the original phase-18 criteria. None is enabled by the portfolio checklist.

## Report

Reports contain the source digest, assignments, unmatched ride counts, match rates, total/mean pickup ETA and
single-run computation times. `mean_eta_reduction_percent` is null when matched ride sets differ or the baseline
mean is zero. Elapsed times include a cold invocation and must not be presented as sustained throughput.

The policy first maximizes feasible match count, then minimizes total ETA. It does not yet implement live
reservation, scheduling priority or larger-window fallback. See [ADR-032](../../docs/decisions/ADR-032-bounded-batch-assignment.md)
and the [dispatch adoption gate](../../docs/dispatch-design.md#8-batch-matching-v4-experiment).