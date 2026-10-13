# Portfolio release 1.0.0

This release completes the separately approved [portfolio checklist](../portfolio-completion.md), not the
original [23-phase roadmap](../implementation-plan.md). The published tag is `portfolio-v1.0.0`; publication
requires all five CI jobs to pass for that tag's exact commit. The GitHub release records that final commit
and CI link. This is a local portfolio release, not a production-readiness declaration or a change to the
application's API/versioning policy.

## Included experience

- Quote, book, assign, track, complete and rate a ride through the rider, driver and operations interfaces.
- Durable ride/dispatch state, idempotency, conditional reservations, invariant checks and concurrency tests.
- Valkey live location and WebSockets, optional Kafka delivery/dead-letter recovery/replay, and opt-in OSRM,
  ranking experiments, city ownership and computed surge.
- A tested offline batch-matching prototype, without enabling live batch dispatch.
- [Isolated setup](../portfolio-completion.md#isolated-local-setup-p4), a [recorded walkthrough](../demo.md),
  [failure demonstrations](../failure-demonstrations.md), an [operations runbook](../runbooks.md), and documented
  [architecture decisions](../decisions/README.md).

## Verified evidence

The [200-driver characterization](../capacity-report.md#corrected-portfolio-attempt-passed) used runtime
`9ee418c9a03869be803cef15dd37ad718ea0343d`, seed 42 and the unchanged portfolio workload. It completed all 60
rides with no unfinished rides and all eight invariants clean. All 119 post-ramp demand samples showed 200
non-offline drivers; applied location updates averaged 49.94/s in that window. Driver-send-to-rider-WebSocket
receipt p95 was 9.925ms, and application peak sampled memory was 486 MiB within a 1 GiB limit.

The [sanitized JSON](../../spikes/results/portfolio-20261013.json) includes image identity, scenario/report
hashes, distributions, population, counter deltas, resources and limitations. The first failed attempt is
retained and explained: regression-tested simulator corrections enforce trip-distance bounds and record
terminal outcomes through the existing drain deadline. No workload threshold, driving speed or deadline was
loosened. The corrected generator changes the realized trace, so this is not a before/after performance claim.

The evidence revision `e49c743eb4145151d49d6bb433c3938cbfa97746` passed
[all five CI jobs](https://github.com/Deepika-1201/ride-hailing-platform/actions/runs/38413814197): Java build/tests,
simulator and spike checks, replicated Kafka tests, web build/tests, and the container workflow with recorded
simulator and browser lifecycle checks. Local simulator race/vet checks and documentation/data reconciliation
also passed. The final release revision changes only release documentation and is separately CI-gated.

## Known limits

- The earlier intermittent missing-offer Java test remains an explicitly [owner-accepted unresolved
  limitation](../portfolio-completion.md#accepted-reliability-limitation-p3). Passing repeats are not a root-cause
  fix; all assertions remain active and recurrence reopens verification.
- The passing measurement had five initial Valkey subscription timeouts followed by client reconnects, one
  OSRM no-route outcome and 46 rating HTTP 409 responses. Zero simulator errors does not mean zero backend
  warnings. Full counts and the separate earlier CI assignment-latency miss are disclosed in the report.
- This is one modest local workload. It does not certify 2,000 drivers, browser-render latency, sustained Kafka
  throughput, cloud capacity, host/disk-loss durability or improved dispatch outcomes.
- Payments are mocked, notifications are log-only, and seeded credentials/local signing keys are not suitable
  for public deployment. Raw captures and tokens are private; only sanitized results are attached.
- Live batch offers, service extraction, multi-city scaling, AWS/Terraform and cloud fault engineering remain
  future roadmap work. Phases 0-15 are complete; later phases retain their original gates.

## Reproduce

Use the [isolated setup and cleanup commands](../portfolio-completion.md#isolated-local-setup-p4), then the
[demo walkthrough](../demo.md). For the measured scenario, follow the [capacity-report recipe](../capacity-report.md#portfolio-characterization-workload)
with real OSRM data, AC power and an open lid. Use a fresh Compose project and capture directory; retain failed
results and do not delete another project's database. Published numbers are observations, not guarantees for
another machine or run.