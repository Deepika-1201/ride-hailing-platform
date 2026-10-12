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
| P2 | Finish and publish the existing batch prototype | 3-6 h | In progress | Solver, tiled preparation and harness tested together; limitations documented; coherent commit and exact-commit CI green |
| P3 | Close reliability gaps | 6-12 h | Pending | Intermittent failures investigated and tracked; regressions and appropriate repetitions pass without weakened assertions; full local and CI gates pass |
| P4 | Verify clean setup, startup, demo and cleanup | 3-6 h | Pending | Fresh isolated setup exercised with documented prerequisites, seed accounts and commands; existing project data preserved |
| P5 | Polish and record the demo | 5-9 h | Pending | Short walkthrough and screenshots covering rider, driver and operations flows; desktop/mobile experience checked; no credentials exposed |
| P6 | Run benchmarks and publish defensible results | 8-18 h | Pending | Reproducible workload, exact revision/environment, actual population, throughput, latency, error/match rates, resources and final invariants reported |
| P7 | Package failure and recovery demonstrations | 4-8 h | Pending | Repeatable commands and evidence for duplicate events, Kafka outage/recovery, Valkey recovery and node restart/draining; expected and observed behavior distinguished |
| P8 | Write troubleshooting and recovery runbooks | 4-7 h | Pending | Verified health/metrics checks, stuck-ride diagnosis, event recovery, startup failures and safe cleanup instructions |
| P9 | Refine the portfolio narrative | 4-8 h | Pending | Concise README entry path, architecture overview, engineering trade-offs, verified results and implemented/experimental/future-work table linked to deeper docs |
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

## Evidence log

| Date | Item | Verified progress | Still open |
|---|---|---|---|
| 2026-10-13 | P1 | Separate scope and checklist established; original phase plan preserved | Portfolio release itself remains in progress |
| 2026-10-13 | P2 | Combined `build batchMatchingExperiment` passed in 9m 31s: 1,268 tests across 153 suites, no failures/errors/skips, including 10 solver/harness and 8 preparation tests; final-assembly deadline regression and preparation limits documented | Publish the coherent milestone and verify exact-commit CI |
| 2026-10-13 | P3 | Failure-only task/decision diagnostics retained for the earlier intermittent missing-offer race; isolated and full reruns passed | Root cause not established; do not describe it as fixed |

Use this log for portfolio delivery evidence. Existing ADRs, test reports and phase records remain the sources for
their respective implementation and verification details.