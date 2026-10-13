# Capacity report

## Status

Phase 16 is **not certified**. The first sustained laptop attempt on 2026-10-12 was interrupted by loss of host-to-VM forwarding. No valid latency percentile or sustained population result is available from that attempt. Cloud-tier measurements have not run.

## Laptop baseline

The unchanged [scenario](../simulator/scenarios/laptop-baseline.yaml) specifies:

- 2,000 drivers, starting across five minutes, followed by thirty further minutes of demand.
- 1,200 rider agents, 1,200 requested trips per hour, normal driving speed and no injected faults.
- Up to fifteen minutes to drain existing rides after demand ends.
- At least 500 bookings, no unfinished rides, match rate at least 85%, no reported errors, and position-receipt latency p95 at most 1,000 ms.
- Clean end-of-run invariant checks and complete metrics sampling.

The simulator measures elapsed time from a driver's send to the corresponding rider WebSocket receipt. This includes the matching-index and tracking path, but **does not measure browser painting**. Browser-render evidence is still needed before claiming the full rider-screen part of NFR-5.

## Interrupted attempt

| Field | Value |
|---|---|
| Application revision | `0f1575f7e61a4202c5fea8485b589bd1cdeb50c3` |
| Capture started | `2026-10-12T10:11:38Z` |
| Local capture | `build/capacity/laptop-20261012T101138Z` (ignored; contains private refresh tokens) |
| Host | Apple Silicon macOS 13.4; AC power and open lid verified before starting |
| Application | Validated Java 25 jar in `eclipse-temurin:25-jre`; all roles; Valkey enabled; Kafka disabled |
| Memory limits | App 1 GiB container / 768 MiB maximum heap; PostgreSQL 512 MiB; Valkey 256 MiB; OSRM 768 MiB |
| Isolation | Separate `ride-capacity` Compose project and database; existing ride-project data untouched |
| Routing | Existing Bengaluru OSRM data, mounted read-only; live routing preflight passed |
| Workload | All agents signed in; 684 generated trips over the planned 35 minutes |
| Outcome | Stopped after approximately fourteen minutes; collector exit code 1 |

The host-facing API and management ports stopped accepting connections shortly after sign-in. The host Docker socket also became unreachable. The capture retained one valid metrics sample and recorded 58 failed scrapes; there is no final simulator report or end-of-run invariant report.

Checks made through `colima ssh` found Docker active and the application still running with `OOMKilled=false`; its in-VM readiness endpoint answered `UP`. This evidence identifies a loss of host access, **not an application OOM**. The last available resource sample showed 415 MiB of the app's 1 GiB limit, but the short sample window is not capacity evidence. Restarting Colima restored host connectivity; the precise forwarding failure cause remains unconfirmed.

The failed capture, app logs and original database volume were retained. A fresh retry stack was prepared with the same resource limits, but the collector refused the next attempt because the Mac had switched to battery power. No second measurement began.

## Collector follow-up

The [collector](../scripts/laptop-tier.sh) now stops its simulator after three consecutive failed metrics scrapes and retains a stop reason and nonzero exit code. A successful scrape resets that streak, but any failed scrape still invalidates the final capture. The simulator, samplers, log pipe and wake assertion have explicit cleanup. Resource samples use non-interactive Docker snapshots rather than terminal-formatted streaming output.

Tests exercise normal completion, an unavailable endpoint, recovery between failure streaks, the exact three-failure boundary and temporary-pipe cleanup. Existing duration, population, speed and sampling-completeness checks remain unchanged.

## Remaining evidence

1. Repeat the full baseline on AC power with an open lid and stable host-to-VM connectivity; preserve complete metrics and container samples.
2. Verify sustained driver population, location rate, latency distribution and all final invariants, not just the scenario's requested population.
3. Measure rider-screen rendering under load before declaring NFR-5 complete.
4. Record any tuning with a before/after comparison; do not weaken the scenario's thresholds to obtain a pass.

## Portfolio characterization workload

The separate [portfolio scenario](../simulator/scenarios/portfolio-baseline.yaml) is prepared for checklist P6.
It has **not been executed or certified**. It is a modest single-city characterization run, not a substitute for
the original laptop baseline or a maximum-capacity test.

| Setting | Fixed before execution |
|---|---|
| City and seed | Bengaluru, seed 42 |
| Population | 200 drivers and 200 riders, starting at seeded account 101 |
| Demand window | 11 minutes total: one-minute driver ramp and ten further minutes of demand |
| Drain budget | Up to ten additional minutes |
| Offered demand | 360 requests/hour with the existing central-zone weights, scenario hour 18 |
| Trip lengths | 1,200-2,500 metres |
| Driver behavior | Normal speed, 90% base acceptance with distance penalty, 3s median/6s p95 response distribution |
| Faults and cancellations | No injected faults, no configured post-assignment cancellation or no-show |
| Acceptance checks | At least 40 bookings and 30 completions, zero unfinished rides, match rate at least 85%, position-receipt p95 at most 1,000ms, zero reported errors |

These are workload and acceptance settings, not observed values. Model-generated arrivals and actual bookings,
online population and throughput must be reported from the run, not inferred from this table. The same scenario
must be used for comparisons; a changed workload or threshold is a new experiment, not a successful rerun.

Use an isolated, healthy API/Valkey/PostgreSQL stack and the real OSRM dataset, on AC power with the lid open.
The compact PMTiles browser fixture is not an OSRM dataset. Record the application image/revision, runtime roles,
Kafka and routing settings, host/VM resources, container limits and any local networking workaround before load.
The collector's Git revision identifies the checkout, not independently the deployed binary, so verify both.

From the repository root, with endpoints set to that isolated stack:

```bash
export API_URL=http://127.0.0.1:18080 MANAGEMENT_URL=http://127.0.0.1:18081
export OSRM_URL=http://127.0.0.1:15000 WS_URL=ws://127.0.0.1:18080/ws
SCENARIO="$PWD/simulator/scenarios/portfolio-baseline.yaml" SAMPLE_INTERVAL=5 \
	bash scripts/laptop-tier.sh "$PWD/build/capacity/portfolio-$(date -u +%Y%m%dT%H%M%SZ)"
```

The existing collector keeps its power guard, scrape-failure fail-fast behavior and sampling-completeness checks.
Keep raw tokens and captures private. Publish a sanitized report with scenario/revision identifiers, actual
population and request counts, rates, p50/p95/p99 where available, errors, resources and the end-of-run invariant
result. Include failed attempts and routing fallbacks. This still measures driver-send-to-rider-WebSocket receipt,
not browser paint; functional screenshots are not latency evidence.

On 2026-10-13 preparation was verified with scenario parser/guard tests while the development Mac was on battery.
No long run was started. The original 2,000-driver, 35-minute demand baseline and its phase-16 criteria are unchanged.