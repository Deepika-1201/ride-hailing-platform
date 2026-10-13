# Failure and recovery demonstrations

These are reproducible automated demonstrations of the implemented recovery paths, using the existing integration
tests rather than a separate failure-injection subsystem. They support portfolio item P7 in the
[portfolio checklist](portfolio-completion.md), not completion of the original cloud failure-engineering phase.

## Run the demonstrations

Prerequisites: JDK 25, Docker/Colima configured for Testcontainers, and enough memory for the temporary test
database, Valkey and Kafka containers. No running demo stack or paid service is required. Initial dependency and
image downloads need network access. Use AC power and keep the laptop awake during a run.

From the repository root:

```bash
./gradlew test --console=plain \
  --tests '*KafkaOutboxIntegrationTests.aBrokerPauseLeavesTheBatchPendingAndTheSameTransportRecoversWithAFreshProducer' \
  --tests '*EventRecoveryTests.normalReplaySkipsHandledEventsWhileExplicitReprocessingOnlyClearsTheTargetConsumer' \
  --tests '*KafkaRuntimeTests.theApplicationPublishesAndConsumesThroughKafkaAndRestartsItsWorkers' \
  --tests '*ValkeyRecoveryTests.matchingComesBackWithinTenSecondsAfterValkeyLosesEverything' \
  --tests '*DrainTests.drainingANodeMovesEveryClientToAnotherWithoutLosingARide'
```

Add `--rerun-tasks` when a fresh execution is needed instead of Gradle's up-to-date result. A focused run replaces
the usual test reports and does not perform the full API/event coverage gate. Preserve earlier reports first if
you need them, and use the normal full build for the release gate.

The report is `build/reports/tests/test/index.html`; machine-readable cases and captured logs are under
`build/test-results/test`. Success means the assertions below executed and passed, not simply that services
started or that a log line was printed. A timeout or skipped case is not a successful demonstration.

## What each case proves

| Case | Injected condition | Assertions that establish recovery | Limits |
|---|---|---|---|
| Broker pause | Pause the test broker after one publish in a relay batch | The batch remains unpublished in PostgreSQL after the timed-out operation; after unpause and lease turnover, the same transport resumes with a fresh producer and the batch drains | Local single-broker process interruption; not host/disk-loss durability or sustained throughput |
| Duplicate replay | Replay events already handled by a consumer, then explicitly request reprocessing | Ordinary replay skips handled effects; explicit reprocessing clears only the target consumer's inbox state | Database inbox/effect semantics; external side effects still need their own idempotency |
| Worker restart | Stop the Kafka application workers with pending events and a queued replay | Restart resumes publication and consumption, completes the retained replay, and preserves expected effect counts and offsets | Controlled application lifecycle stop/start, not a sudden OS process kill |
| Valkey data loss | Clear only the test Valkey instance, while simulated driver updates and the live-index watch continue | The candidate set is initially empty; all five drivers become candidates again within the test's ten-second bound | Small functional recovery case, not a large-population recovery benchmark |
| Node drain | Close one of two application nodes while six rider/driver WebSocket clients are connected | The old node refuses new work, clients reconnect through the other node, and all three rides finish with their status pushes received | Two local nodes and a controlled drain; not an AWS load-balancer or zone-failure test |

### Implementation references

- [Broker pause and relay recovery](../src/test/java/com/ridehailing/platform/outbox/KafkaOutboxIntegrationTests.java).
- [Replay and inbox semantics](../src/test/java/com/ridehailing/platform/outbox/EventRecoveryTests.java).
- [Kafka runtime restart and durable replay](../src/test/java/com/ridehailing/platform/outbox/KafkaRuntimeTests.java).
- [Valkey matching recovery](../src/test/java/com/ridehailing/dispatch/ValkeyRecoveryTests.java).
- [Two-node client drain and ride completion](../src/test/java/com/ridehailing/realtime/DrainTests.java).

The runtime case also rejects unsafe topic settings and demonstrates dead-letter mirroring while the test
database temporarily rejects mirror writes. The source assertions, not this summary, define the precise guarantees.

## Failed offer-expiry investigation

The additional P3 regression deliberately holds the pending offer's driver-availability row locked while one
timer attempt runs. The normal database statement timeout makes that attempt fail after it has tried to update
the offer, search task and outbox. The test checks that the transaction rolls all of those changes back: the
offer remains pending, the driver remains reserved, no expiry event is committed, and the timer records its
error and retry schedule. Once the lock is released, a retry commits one expiry event, restores matching and
allows another ride to receive an offer from that driver.

```bash
./gradlew test --console=plain \
  --tests '*OfferTests.aFailedExpiryLeavesTheDriverReservedUntilItsRetryCommits'
```

This is a deterministic local lock-timeout/recovery test, not a reproduction of the original intermittent
missing-offer incident. It proves why "the timer helper attempted work" is not equivalent to "expiry completed"
and validates the rollback and recovery path. The earlier race now checks expiry, availability and matching
preconditions explicitly; a passing run does not establish what happened in the original failure.

## Safe operation

- Let the fixtures create and clean up their test containers. Do not replace their endpoints with a shared demo,
  production database or broker.
- Do not manually run `FLUSHALL`, pause arbitrary containers, delete volumes or prune Docker globally to reproduce
  these cases. The destructive operations are scoped by the test fixtures and have explicit recovery/cleanup.
- Expected exception logs appear when a failure is injected. Judge the outcome by the final test result and
  assertions; an expected error log is not proof that recovery failed.
- If a run is interrupted, inspect the remaining container names and labels before cleanup. Preserve other projects
  and the original phase-15 database. Never treat interruption as a passing result.
- Keep raw artifacts private until reviewed. They may contain test identities or internal diagnostic data even
  though the accounts and resources are synthetic.

## Evidence record

The tests are part of the normal suite. The exact command above was freshly executed on 2026-10-13 against
revision `6dd912911a56d253b19d3e8ceac1b0eb91f20f1d` and passed in 1m 6s. All five requested cases and ten
architecture checks passed across six suites, with no failures, errors or skips. Valkey matching recovered all
five drivers in 5,665 ms in that run, below the test's ten-second bound. This is an observation from this local
fixture, not a percentile, throughput result or guarantee for a larger deployment.

The local run log is `/tmp/ride-portfolio-failure-demonstrations.log`; preserved reports are under
`build/portfolio/evidence/p7-6dd9129`. Raw logs remain private until reviewed. The checks themselves are runnable
from the committed test sources above and remain part of regular CI.

The earlier intermittent missing-offer race remains a separate P3 investigation. Passing these recovery cases
does not establish a root cause or close that issue. Likewise, the [capacity report](capacity-report.md) retains
its original unverified targets and the [23-phase plan](implementation-plan.md) remains unchanged.