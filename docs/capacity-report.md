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
Its first measured attempt on 2026-10-13 **failed acceptance**, as recorded below. It is a modest single-city
characterization run, not a substitute for the original laptop baseline or a maximum-capacity test.

| Setting | Fixed before execution |
|---|---|
| City and seed | Bengaluru, seed 42 |
| Population | 200 drivers and 200 riders, starting at seeded account 101 |
| Demand window | 11 minutes total: one-minute driver ramp and ten further minutes of demand |
| Drain budget | Up to ten additional minutes |
| Offered demand | 360 requests/hour with the existing central-zone weights, scenario hour 18 |
| Trip lengths | 1,200-2,500 metres straight-line; actual road distance and duration can be longer |
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

Preparation was verified on 2026-10-13 with scenario parser/guard tests while the Mac was on battery; no load ran
at that point. The later measured attempt began only after AC power and the open lid were verified. The original
2,000-driver, 35-minute demand baseline and its phase-16 criteria are unchanged.

### First portfolio attempt: failed

| Field | Observed value |
|---|---|
| Application/simulator revision | `1bf057f032d3d009176d1ddfe7b53f0534e3f59b` |
| Capture | `build/capacity/portfolio-20261013T125811Z` (private raw evidence retained) |
| Environment | AC power, open lid; isolated fresh PostgreSQL/Valkey/OSRM/app stack; Kafka disabled; real OSRM data; app OSRM enabled for Bengaluru |
| Memory limits | App 1 GiB, PostgreSQL 512 MiB, Valkey 256 MiB, OSRM 768 MiB |
| Workload | 200 configured drivers and 200 configured riders; seed 42; 61 generated trips |
| Elapsed | 24 minutes, including the existing hard cleanup allowance after demand and drain |
| Ride report | 61 booked, 61 assigned, 56 completed, 5 unfinished; no reported cancellations or not-found outcomes |
| Position receipt latency | p50 3.812ms, p95 11.7ms, p99 26.86ms, maximum 665.123ms; 7,875 samples |
| Other checks | Zero reported errors, network errors, retries, route misses or WebSocket reconnects; all eight invariants checked with zero violations |
| Collection | 286 usable five-second metric samples, maximum observed gap 7s; failed-summary status retained |
| Failed criterion | `rides.unfinished <= 0`, observed 5; collector exit 1 |

The run is not a successful baseline. Investigation found two simulator defects, each reproduced by a focused
test. Destination sampling returned its twentieth rejected point even when outside the configured bounds; the
unchanged portfolio scenario/seed reproduced a 9,491m straight-line trip against the 2,500m maximum. One retained
unfinished ride had a 14,342m OSRM route. Sampling now returns only valid points or an explicit bounded-generation
error before agents start; the scenario settings and thresholds have not been loosened.

Riders also stopped observing immediately when drain cleanup began, without recording a successful cancellation
or a subsequent completion in the already-existing hard cleanup window. The retained database showed 59 completed
and two in-trip rides when the app was stopped, compared with the report's 56 and five. The corrected rider keeps
observing until a terminal state or the original hard deadline. Tests prove cancellation/completion accounting and
that hard cancellation still stops observation; no extra time or faster driving was added.

The failed capture and database are retained. A corrected generator produces a different deterministic trace
(60 planned trips for seed 42), so the next run must identify its new code revision and must not be presented as
a like-for-like performance improvement. A read-only preflight of that corrected trace found all straight-line
distances within 2,500m, while road routes still reached about 6.4km and 651s. These are planning observations,
not a passing workload result. Rerun acceptance and final CI remain required.

## Offline capture summary

The simulator can inspect an existing collector directory without running load, contacting the platform, or
reading its token file. From the repository root:

```bash
cd simulator
go build -o ../build/sim ./cmd/sim
../build/sim summarize -capture /path/to/existing-capture -out /path/to/new-summary.json
```

Omit `-out` to emit JSON to standard output. Output files must be new; existing evidence is never overwritten.
The binary exits 0 when the implemented capture checks pass, 1 when the summary reports incomplete or failed
evidence, and 2 for command/output errors. A non-passing capture still produces its diagnostic summary.

The summary records scenario/report SHA-256 digests, explicitly configured populations, selected observed
metrics/distributions, recomputed scenario checks, invariant counts and sample timing. It does not trust stored
`checks.met` flags or infer achieved population from the scenario. It requires all eight unique invariants with no
violations, a successful collector exit, the required before/after metrics and resource files, and samples that
cover the run with the collector's interval tolerance. Known percentile checks require matching distribution
data. Present ride, offer and error counts must be nonnegative integers. Derived match/completion/cancellation/
not-found rates and offers-per-ride must agree with their underlying counts; missing inputs or zero denominators
are rejected. Outcome counts cannot exceed bookings, and a complete outcome set must sum to bookings. Rates may
be absent for a zero-booking report, matching the recorder's format. Invalid or linked input files and oversized
reports are rejected.

Raw problem strings, violation identities, endpoint details, run logs and token files are not copied into the
summary. Review configured scenario names and check labels before publishing, since they are user-supplied
metadata. Prometheus contents and resource rows are not semantically parsed by this command: actual concurrent
population, resource peaks, deployed-binary identity and broader performance claims still need independent
review. `CHECKS_PASSED` is not capacity certification and does not complete the original phase-16 criteria.

The helper was verified on 2026-10-13 with the simulator's full race tests and vet checks. It also inspected the
retained interrupted laptop capture above and returned `CHECKS_FAILED`: the final report and successful exit
were absent, collection errors were recorded, and only one usable metric sample existed. This did not execute
a new workload. File hashes identify the supplied artifacts; they do not authenticate them or prove that copied
scenario thresholds were never edited. Compare the scenario hash and deployment evidence before publishing results.

A follow-up regression demonstrated that the earlier summary accepted inflated derived rates and inconsistent
counts. The added arithmetic checks reject those captures and accept fractional rates generated by the real
recorder. These checks improve artifact consistency; they do not independently verify the recorded observations.