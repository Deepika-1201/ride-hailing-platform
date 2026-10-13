# Portfolio completion

| Field | Value |
|---|---|
| Scope approved | 2026-10-13 |
| Status | In progress; not a release-completion claim |
| Goal | A reproducible, well-explained ride-hailing portfolio project with evidence for its claims |
| Relationship to the roadmap | Additional release checklist; the original 23 phases remain unchanged |

## Relationship to the 23 phases

The [implementation plan](implementation-plan.md) remains the authoritative roadmap for phases 0-22. This
document does not remove, renumber, replace, complete, or weaken any phase or its exit criteria. Completing this
portfolio release will not mean that all 23 phases, the original capacity targets, or production readiness are complete.

Work that also satisfies a phase requirement can supply evidence to that phase. Its status changes only when its
original criteria are met. Portfolio progress is tracked separately in the checklist below, not substituted for
phase-count or full-roadmap progress.

## Accepted release scope

- Retain the implemented booking-to-completion lifecycle, driver location, WebSockets, operations views, simulator,
  Kafka delivery/recovery, routing, ranking experiments, city ownership and computed surge.
- Preserve conservative defaults and document every opt-in configuration. Payments remain a mock provider and
  notifications remain log-only; neither is presented as a live financial or messaging integration.
- Finish and verify the existing offline batch-assignment and tiled matrix-preparation prototype. Keep it
  experimental and separate from live dispatch. A synthetic matrix result is not a matching-outcome claim.
- Prioritize reproducibility, correctness, measured behavior, failure demonstrations, and clear presentation over
  additional feature breadth. No new major runtime subsystem is required just to finish this release.
- Live batch offers, larger-window policies, service extraction, multi-city partitioning, read replicas,
  AWS/Terraform deployment and extensive cloud fault injection are not blockers for this portfolio release.
  They remain on the original roadmap, not cancelled or declared complete.
- No paid infrastructure, public deployment, real payment integration, or destructive reset of existing data is
  authorized by this checklist. Reuse existing tools, tests and isolated local environments.

## Work checklist

Estimates are focused engineering effort for an AI-assisted developer familiar with the project, not promises of
calendar duration. One focused day is approximately six productive hours. Automated waits can add elapsed time;
benchmark/environment failures and intermittent test failures are the largest uncertainties.

| ID | Work item | Estimate | Status | Completion evidence |
|---|---|---|---|---|
| P1 | Freeze scope and define release checklist | 1-2 h | Complete | Accepted scope, exclusions and independent release criteria recorded here |
| P2 | Finish and publish the existing batch prototype | 3-6 h | Complete | Solver, tiled preparation and harness tested together; limitations documented; coherent commit and exact-commit CI green |
| P3 | Close reliability gaps | 6-12 h | In progress | Intermittent failures investigated and tracked; regressions and appropriate repetitions pass without weakened assertions; full local and CI gates pass |
| P4 | Verify clean setup, startup, demo and cleanup | 3-6 h | Complete | Fresh isolated setup exercised with documented prerequisites, seed accounts and commands; existing project data preserved |
| P5 | Polish and record the demo | 5-9 h | Complete | Short walkthrough and screenshots covering rider, driver and operations flows; desktop/mobile experience checked; no credentials exposed |
| P6 | Run benchmarks and publish defensible results | 8-18 h | In progress | Reproducible workload, exact revision/environment, actual population, throughput, latency, error/match rates, resources and final invariants reported |
| P7 | Package failure and recovery demonstrations | 4-8 h | Complete | Repeatable commands and evidence for duplicate events, Kafka outage/recovery, Valkey recovery and node restart/draining; expected and observed behavior distinguished |
| P8 | Write troubleshooting and recovery runbooks | 4-7 h | Complete | Verified health/metrics checks, stuck-ride diagnosis, event recovery, startup failures and safe cleanup instructions |
| P9 | Refine the portfolio narrative | 4-8 h | Complete | Concise README entry path, architecture overview, engineering trade-offs, verified results and implemented/experimental/future-work table linked to deeper docs |
| P10 | Verify and publish the release | 2-4 h | Pending | Final revision passes build, CI and demo checks; release notes point to evidence and disclose limitations and any unresolved issue |
| Total | Portfolio release only | 40-80 h | In progress | Approximately 7-14 focused days; not the remaining effort for all 23 phases |

## Execution order

1. Complete P2 as one coherent implementation/tests/documentation milestone. Do not add live batch dispatch while
   closing the existing prototype.
2. Investigate P3's known intermittent missing-offer race, then verify P4's fresh-environment workflow. A passing
   rerun alone is not evidence that an intermittent failure was fixed.
3. Capture P5 and P7 from reproducible workflows, reusing the existing browser, simulator and integration tests.
4. Run P6 once the environment is stable and prerequisites are met. Do not run unattended long laptop measurements
   on battery or with sleep interruptions, or present interrupted captures as successful results.
5. Write P8 and P9 from the commands and evidence actually verified above. P10 is the final gate, not another
   feature-development phase. Update statuses and the evidence log as work is verified.

## Measurement and claim rules

- Keep the original 2,000-driver laptop target and its thresholds unchanged. Its [capacity report](capacity-report.md)
  currently says not certified; a smaller valid portfolio benchmark must be labeled with its actual load and must
  not be used to mark phase 16 complete.
- Distinguish driver-send-to-WebSocket-receipt latency from browser-render latency. Do not claim the rider-screen
  target without rendering evidence.
- Separate local functional broker failover from host/disk-loss durability, and finite throughput experiments
  from sustained capacity certification. No cloud-scale claims without cloud measurements.
- Report cold/single-run solver timing as such. The [batch comparison](../spikes/batch-matching/README.md) is an
  offline ETA-matrix experiment, not a live reservation or dispatch-adoption result.
- Report regressions, failed captures and unresolved issues alongside successful reruns. Never change a threshold,
  skip a meaningful test, or relabel an experimental feature merely to obtain a portfolio-complete status.
- Preserve raw evidence privately when it includes tokens or personal data. Publish sanitized summaries, commands,
  workload/configuration identifiers and the exact tested revision.

## Release gate

All ten checklist items must have reviewed evidence, or an explicitly accepted and documented exception, before
calling this portfolio release complete. At that point a reviewer should be able to:

1. Clone the repository and run the documented local experience without paid services.
2. Follow a ride from quote through assignment, live tracking, completion and the mocked post-trip workflows.
3. Inspect architecture decisions and the concurrency, contract and recovery tests behind important guarantees.
4. Reproduce the published workload and understand precisely what its results do and do not prove.
5. Find operational recovery instructions, known limitations and the still-open 23-phase roadmap.

## Isolated local setup (P4)

Run these commands from the repository root. Docker with Compose, `curl`, and Python 3.8 or later are required;
the container build supplies Java and Node. Host-side Java tests additionally require JDK 25. The commands use a
separate Compose project, alternate host ports and the committed compact basemap, not the full OSRM dataset.
Base-image/package downloads still need Internet access. These images and seed credentials are for local use,
not public deployment.

```bash
export POSTGRES_PORT=15434 VALKEY_PORT=16380 API_PORT=18080 MANAGEMENT_PORT=18081 WEB_PORT=18088
export MAPS_DIR="$PWD/web/testdata"
export RIDE_KAFKA_ENABLED=false RIDE_ROUTING_OSRM_ENABLED=false
docker compose -p ride-portfolio-check up --build --detach postgres valkey app
curl --fail --retry 20 --retry-all-errors --retry-delay 1 --connect-timeout 2 --max-time 5 \
  http://127.0.0.1:18081/actuator/health/readiness
docker compose -p ride-portfolio-check --profile simulator up --build --detach --no-deps web
BASE=http://127.0.0.1:18080 MANAGEMENT=http://127.0.0.1:18081 ./scripts/demo.sh
```

Open <http://localhost:18088>. The seeded local sign-in code is `123456`; the manual rider/driver accounts and
their roles are listed in the [main README](../README.md#simulator-and-web-app). The script chooses fresh seeded
accounts and exercises booking, dispatch, trip completion, mocked payment, ratings, earnings and the operations
timeline. It does not measure browser latency or certify capacity. The compact map fixture is suitable for this
demo and the browser tests; enabling the routing profile requires the actual OSRM data from the normal map setup.

Host-port overrides preserve container-to-container addresses. Without overrides, the original ports remain
5434, 6380, 8080, 8081, 8088 and 5000; the map directory remains `.maps`. The repeatable configuration checks are
`node --test scripts/compose-config.test.mjs` (Node 22 in CI). Use another unused set of ports if these are taken.
Choose a new project name if `ride-portfolio-check` already contains data you want to retain.

### Local download caveat

The source build on the development Mac initially timed out following the Gradle distribution redirect. A
build-only host override succeeded without changing Gradle or runtime networking:

```bash
docker build --add-host release-assets.githubusercontent.com:185.199.110.133 -t ride-portfolio-check-app .
docker compose -p ride-portfolio-check build postgres
docker compose -p ride-portfolio-check up --no-build --detach postgres valkey app
```

The address was verified for this network during the recorded run; it is not a permanent GitHub address or a
general recommendation. Prefer the normal source build. If that workaround is needed on another network,
diagnose the redirect and connectivity first. Do not claim a cache-free build: base/package layers may be reused.
The verified local run used freshly created project data and built the application from the current source.

### Safe cleanup

To stop this isolated stack while retaining its database:

```bash
docker compose -p ride-portfolio-check --profile simulator down
```

For a disposable verification run only, replace `down` with `down --volumes` to also delete this project's mock
data. Check the project name before doing so. Neither command targets the original `ride-phase15` database or
other Compose projects. Do not run global Docker prune commands as part of the demo.

## Evidence log

| Date | Item | Verified progress | Still open |
|---|---|---|---|
| 2026-10-13 | P1 | Separate scope and checklist established; original phase plan preserved | Portfolio release itself remains in progress |
| 2026-10-13 | P2 | Published as `3f5ad9f`; combined `build batchMatchingExperiment` passed in 9m 31s: 1,268 tests across 153 suites, no failures/errors/skips, including 10 solver/harness and 8 preparation tests; [all five CI jobs passed](https://github.com/Deepika-1201/ride-hailing-platform/actions/runs/38293995045) | Live dispatch integration remains on the original roadmap, not part of this prototype milestone |
| 2026-10-13 | P3 | Explicit expiry, availability and matching preconditions passed 1,000 forced repetitions. The [deterministic lock-timeout regression](failure-demonstrations.md#failed-offer-expiry-investigation) verifies atomic rollback and successful retry. The combined full build passed in 10m 14s: 1,269 tests across 153 suites, including 17 offer tests, 14 dispatch race cases and contract coverage, with no failures/errors/skips | The failure mechanism is demonstrated, but the original intermittent incident is not reproduced or attributed; do not describe it as fixed or weaken the assertions |
| 2026-10-13 | P4 | Application source `3f5ad9f` built with the documented local workaround; fresh project data, readiness, full scripted demo, web entry/API proxy/PMTiles ranges and both Compose configuration tests passed. Project containers, network and disposable volume removed; the original phase-15 database retained the same container ID and stayed running. Evidence is retained privately under `build/portfolio/evidence/setup-3f5ad9f` | Not a cache-free, cloud, capacity or recorded browser-demo claim; the source-download caveat remains documented |
| 2026-10-13 | P5 | Published as `6dd9129`; [all five CI jobs passed](https://github.com/Deepika-1201/ride-hailing-platform/actions/runs/38375754116). Recording passed in 1.4m; normal browser run in 1.1m; web build, 26 unit tests and strict E2E typecheck passed. Full Java build: 1,268 tests, no failures/errors/skips. Seven reviewed screenshots and commands in the [walkthrough](demo.md); demo services stopped and data retained | Raw videos remain ignored; no capacity or production-readiness claim |
| 2026-10-13 | P7 | [Failure guide](failure-demonstrations.md) packages five existing recovery cases. Fresh execution on `6dd9129` passed in 1m 6s: five demonstrations plus ten architecture checks, no failures/errors/skips; five-driver Valkey recovery observed at 5,665 ms. The same test sources passed full CI above | No claim of cloud/host-loss or large-population certification; P3's intermittent failure remains separate |
| 2026-10-13 | P6 | Separate 200-driver/200-rider normal-speed [characterization workload](capacity-report.md#portfolio-characterization-workload) published as `5c46e40` with [green CI](https://github.com/Deepika-1201/ride-hailing-platform/actions/runs/38385396102). The [offline summary command](capacity-report.md#offline-capture-summary) passes simulator race/vet checks and correctly rejects the retained interrupted capture; it recomputes checks and omits token/raw-error content. Original laptop baseline unchanged | No measured result yet; execution requires AC power, an awake host, real OSRM data and a stable isolated environment. Summary output is not certification |
| 2026-10-13 | P8 | [Local runbook](runbooks.md) checked against actual API and metric definitions; 37 operations/gauge/invariant/recovery and architecture cases freshly passed in 21s on `d983b6f`; links, operation paths and all-profile cleanup syntax verified. Published as `5a25dfe`; [all five CI jobs passed](https://github.com/Deepika-1201/ride-hailing-platform/actions/runs/38382776108). Reports retained under `build/portfolio/evidence/p8-d983b6f` | Local procedures only, not cloud operations certification; final release gate remains P10 |
| 2026-10-13 | P9 | Published with `5a25dfe` and green CI above. README leads with the real demo, architecture decisions, evidence links and implemented/experimental/future-work distinction; local links and claim limits reviewed | Benchmarks and P3's unresolved issue remain disclosed; original roadmap unchanged |

Use this log for portfolio delivery evidence. Existing ADRs, test reports and phase records remain the sources for
their respective implementation and verification details.