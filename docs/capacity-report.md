# Capacity report

## Status

Phase 16 is **not certified**. The first sustained laptop attempt on 2026-10-12 was interrupted by loss of host-to-VM forwarding. No valid latency percentile or sustained population result is available from that attempt. Cloud-tier measurements have not run.

A separate [200-driver portfolio characterization](#corrected-portfolio-attempt-passed) passed on 2026-10-13.
Its measured load, recovered connection failures and single-run limits are reported below; it does not replace
the original phase-16 workload or browser-render requirement.

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
Its first measured attempt on 2026-10-13 **failed acceptance**. After two simulator corrections, a fresh run
**passed the unchanged scenario criteria**. Both attempts are recorded below. This is a modest single-city
characterization, not a substitute for the original laptop baseline or a maximum-capacity test.

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

The failed capture and database are retained. The corrected generator produces a different deterministic trace
(60 planned trips for seed 42), so the new run identifies its code revision and is not presented as
a like-for-like performance improvement. A read-only preflight of that corrected trace found all straight-line
distances within 2,500m, while road routes still reached about 6.4km and 651s. These are planning observations,
not workload measurements. The actual corrected run follows.

### Corrected portfolio attempt: passed

The [sanitized result](../spikes/results/portfolio-20261013.json) was reconciled against the retained simulator
report, deployment metadata, Prometheus samples and Docker resource samples. The raw capture is private at
`build/capacity/portfolio-r2-20261013T135223Z`; neither its tokens nor session/ride identifiers are published.

| Field | Observed value |
|---|---|
| Runtime revision | `9ee418c9a03869be803cef15dd37ad718ea0343d`; [all five CI jobs passed](https://github.com/Deepika-1201/ride-hailing-platform/actions/runs/38407873690), including the recorded simulator and browser workflow |
| Worktree at measurement | One additional hard-deadline regression test was uncommitted; hashes of all non-test simulator files match the recorded revision. Application image identity and scenario/report hashes are in the sanitized result |
| Environment | Apple Silicon, macOS 13.4, 8 GiB host RAM; Colima 4 vCPUs, 4 GiB configured VM memory (4,095,578,112 bytes reported); AC power and open lid verified |
| Runtime | Fresh isolated PostgreSQL/Valkey/app/real-OSRM stack; all four roles, Kafka disabled, default nearest ranking and rule-based surge; original phase-15 PostgreSQL remained running separately |
| Timing | 13:52:29.006483-14:12:26.298186 UTC; 1,197.292s total, including drain; original 11-minute demand and ten-minute drain budget unchanged |
| Population | 200 configured drivers and riders; 200 distinct driver sessions in PostgreSQL; all 119 post-ramp demand samples show 200 non-offline drivers |
| Ride outcomes | 60 generated, booked, assigned and completed; zero cancellations, not-found or unfinished rides; match and completion 100%; 69 offers, 1.15 per ride |
| Realized bookings | 60 over 11 minutes, approximately 5.45/minute; the configured 360/hour is an arrival-model setting, not an achieved-throughput claim |
| Applied location updates | 58,397 total, approximately 48.77/s over the full run; 29,816 across 597s of post-ramp demand, approximately 49.94/s |
| Position receipt latency | 7,431 observations; p50 3.973ms, p95 9.925ms, p99 15.983ms, maximum 143.108ms |
| Assignment wait | 60 observations; p50 3.728s, p95 8.293s, p99/maximum 12.025s |
| Pickup wait and distance | Wait p50 162.743s, p95 312.060s, p99 457.277s; distance p50 659m, p95 1,731m, p99 2,206m |
| Final checks | All six unchanged scenario checks passed; all eight invariants checked, zero violations; independent database query found 60 completed rides |
| Collection | 239 metric samples, nominal interval 5s, maximum gap 6s; 170 Docker snapshots per benchmark container; collector exit 0; offline summary `CHECKS_PASSED` |

| Container | Limit | Peak sampled memory | Peak sampled CPU |
|---|---:|---:|---:|
| Application | 1,024 MiB | 486 MiB | 133.96% |
| PostgreSQL | 512 MiB | 214 MiB | 25.56% |
| Valkey | 256 MiB | 5.172 MiB | 4.27% |
| OSRM | 768 MiB | 252.4 MiB | 3.20% |

Docker CPU uses 100% per logical CPU, so 133.96% is about 1.34 CPUs, not a percentage of the entire four-CPU VM.
These are per-container sampled peaks over the whole capture, not continuous maxima or necessarily simultaneous
peaks. Docker's displayed memory is not JVM heap usage. The sampler waits five seconds after each snapshot,
so its 170 observations have a different cadence from the 239 Prometheus scrapes.

**Errors and recovery:** the simulator reported zero errors, network errors and transport retries; the server
recorded zero HTTP 5xx responses. There were five WebSocket reconnects. All five corresponding subscription
warnings occurred in the first second of the workload: Valkey did not answer within the configured approximately
100ms deadline, the sessions were closed and clients reconnected. The subscription-error counter increased by
five, not zero. There were also 46 HTTP 409 responses on the rating endpoint. These are disclosed even though the
scenario's error and completion checks passed; this is not an error-free-startup claim.

Application routing recorded 955 successful OSRM calls, one no-route outcome, zero fallback calls and zero cache
errors, with 1,129 cache hits and 956 misses. The simulator recorded zero route misses. The application and
simulator use separate routing paths, so their counters are not interchangeable.

For population, `live_drivers` sums non-offline database lifecycle states across categories for Bengaluru; it does
not prove every driver is fresh or immediately matchable. Values were 200 in 225 samples from 13:53:32 through
14:12:24 UTC, including all post-ramp demand samples. Location rates are counter deltas divided by the stated
elapsed window. Analysis used `parse-prometheus-text-format@1.1.1`, omitting only empty `HELP` comments; process
start time stayed constant and applied-update counters never reset. The private capture retains the analysis
program, input hashes and computed resource summary. The application was stopped after collection; both attempt
databases are retained.

The earlier CI scenario on `1bf057f` separately missed its unchanged 60s assignment-p95 threshold (72.119s).
The corrected revision passed at 23.728s with clean invariants, but its generated trace changed (16 rather than
17 bookings); this does not establish the original latency miss's cause or prove all variability is eliminated.
The final candidate `696b864` later reproduced a 140.130s CI miss. A retained local replay of that same 40-driver
scenario identified category supply starvation: one XL ride waited 139.393s, with 26 no-candidate attempts,
three of four XL drivers occupied and the fourth declining a nearly 6km pickup. The ride was assigned about
three seconds after another XL trip completed, with no stuck worker or slow HTTP call.

The owner approved raising **only CI supply to 100 drivers**, preserving 40 riders, the same 16 trips, the 60s
bound and all fault/timing settings. Three local replays passed at assignment p95 23.277s, 9.047s and 9.047s,
with zero unfinished rides and clean invariants. This changes the functional CI fixture, not either benchmark
scenario, and is not a dispatch-performance improvement claim. See the [simulator ADR](decisions/ADR-021-simulator.md#amendment-2026-10-13-sufficient-category-supply-in-ci)
for evidence and trade-offs. The passing portfolio measurement above remains tied to its original recorded
runtime, 200-driver scenario and unchanged thresholds.

The historical missing-offer test remains under the separate [accepted P3 limitation](portfolio-completion.md#accepted-reliability-limitation-p3).
Neither passing run certifies 2,000 drivers, browser painting, sustained Kafka throughput or cloud capacity.

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