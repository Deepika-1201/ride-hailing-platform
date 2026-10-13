# Local portfolio operations runbook

This guide covers the implemented local platform and portfolio checklist item P8. It is not a production
on-call guide, a cloud deployment procedure, or completion of the original phase-21/22 requirements. Keep the
[23-phase roadmap](implementation-plan.md), [capacity limits](capacity-report.md) and
[portfolio checklist](portfolio-completion.md) separate.

## Before changing anything

1. Confirm the application revision, active runtime roles, Compose project and host ports. Use the
   [isolated setup recipe](portfolio-completion.md#isolated-local-setup-p4) when reproducing a problem.
2. Preserve the request ID, affected ride/event/replay ID, timestamps, status codes and relevant logs. Do not
   publish access/refresh tokens, PINs, raw request bodies or unreviewed recovery records.
3. Start with the read-only checks below. Do not edit ride states, reservations, timers, inbox rows or outbox
   publication markers directly to make a symptom disappear.
4. Do not globally prune Docker, flush a shared Valkey instance, remove another project's volume, or switch event
   transport while workers are running. A rollback or interrupted command is not evidence of successful recovery.

## Health and process identity

The following defaults address the isolated portfolio stack. Set them to the actual deployment being inspected:

```bash
export API_URL=http://127.0.0.1:18080
export MANAGEMENT_URL=http://127.0.0.1:18081
export PROJECT=ride-portfolio-check

curl --fail --silent --show-error --connect-timeout 2 --max-time 5 "$API_URL/readyz"
curl --fail --silent --show-error --connect-timeout 2 --max-time 5 "$MANAGEMENT_URL/actuator/health/readiness"
curl --fail --silent --show-error --connect-timeout 2 --max-time 5 "$MANAGEMENT_URL/actuator/info"
docker compose -p "$PROJECT" ps
docker compose -p "$PROJECT" logs --no-color --tail 100 app
```

Readiness currently includes lifecycle readiness state and the database, not independent Kafka or Valkey probes.
An `UP` response does not prove that every downstream feature is healthy, and is not a capacity result.
Inspect process roles in `/actuator/info`: an API without dispatch workers
cannot create offers, and an API without workers cannot execute background payments, notifications or replay.
When roles are split, the in-memory live index is not an appropriate cross-process store; use the documented
Valkey configuration. `ride.workers.autostart=false` deliberately disables automatic polling loops.

The default non-isolated ports remain API 8080, management 8081, PostgreSQL 5434, Valkey 6380 and web 8088. An
HTTP connection failure, an unhealthy process and a healthy process refusing traffic during drain are different
conditions. Capture the status/body before restarting anything.

## Metrics

Read the actual metrics exposed by the inspected instance:

```bash
curl --fail --silent --show-error --connect-timeout 2 --max-time 10 \
  "$MANAGEMENT_URL/actuator/prometheus"
docker stats --no-stream
```

Compare time-separated samples from the same configuration, including error counters and queue/backlog gauges;
a single snapshot does not establish a rate or latency percentile. Labels and some meters appear only after
their feature or city has been used. Counter resets can mean process restart, not recovery. Keep raw samples
outside Git until reviewed, as the [capacity collector](../scripts/laptop-tier.sh) does.

| Prometheus metric | Interpretation |
|---|---|
| `outbox_oldest_unpublished_seconds` | Age of the oldest unpublished outbox event; inspect worker/transport health when it grows |
| `timers_overdue_seconds` | Age of overdue non-parked timers; zero does not mean there are no parked failures |
| `search_tasks_due` | Due search-task count; correlate with dispatch-role activity and decision outcomes |
| `live_drivers{city,category,status}` | Non-offline database availability rows, not proof of fresh locations or active sockets |
| `dispatch_search_attempts_total{outcome}` | Committed attempt outcomes: `OFFERED`, `NO_CANDIDATES`, `ALL_RESERVATIONS_LOST`, `INDEX_UNAVAILABLE` |
| `dispatch_reservation_conflicts_total` | Conditional reservations lost during committed attempts; contention evidence, not double assignments |
| `dispatch_first_offer_seconds_*{city}` | Booking-to-first-offer timer/histogram; do not confuse it with rider-screen location latency |

The first four gauges are refreshed by worker-role pollers every five seconds and are registered on their first
refresh. Missing gauges on an API-only process are not zero-valued measurements. Dispatch outcome counters are
registered at zero; city-specific first-offer metrics appear after that city's first offer.

## Diagnose a ride that is not progressing

Use an authenticated OPS or ADMIN client. These routes are read-only and defined in the
[OpenAPI contract](openapi.yaml):

| Request | Purpose |
|---|---|
| `GET /v1/ops/rides?city_id=blr&status=SEARCHING&limit=20` | Find searching rides in the intended city |
| `GET /v1/ops/rides/{ride_id}/timeline` | Inspect transitions, offers, dispatch decisions, events, charges and notifications |
| `GET /v1/ops/flags?limit=20` | Inspect operational flags rather than changing state blindly |
| `GET /v1/ops/invariants` | Run read-only correctness checks; a successful response is not enough unless `violations` is empty |

Interpret the timeline before deciding a recovery action:

- `INDEX_UNAVAILABLE`: inspect Valkey connectivity and errors. Search retries use bounded backoff; do not replace
  an unavailable index with assumed empty supply or reserve a driver manually.
- `NO_CANDIDATES`: inspect the search radius, category, driver freshness and eligibility, and previously offered
  drivers. A driver visible on the operations map is not necessarily a matching candidate.
- `ALL_RESERVATIONS_LOST`: candidates were found but concurrent conditional reservations did not succeed. Check
  contention and subsequent attempts, not just the first decision.
- Pending offer: verify its expiry and the dispatch role's timer loop. An expiry can fail and be retried; the
  timer's due time alone does not prove the offer ended or the driver became available again.
  The [deterministic expiry-recovery regression](failure-demonstrations.md#failed-offer-expiry-investigation)
  demonstrates the pending/reserved state after rollback and the later successful retry. Do not manually free
  the driver or change the offer status to bypass that recovery path.
- Assigned/in-trip but stale location: distinguish driver connectivity from ride state. Reconnection and status
  reads are expected; repeated command submissions require the same idempotency key and identical body.
- Completed ride awaiting payment/rating: inspect the worker role and its event/notification/replay progress.
  The mock provider can intentionally decline or delay responses. Do not treat a pending payment as successful.

If a manual cancellation is justified, `POST /v1/ops/rides/{ride_id}/cancel` requires a reason and an
`Idempotency-Key`; fees are optional and must be explicitly authorized. Preserve the audit trail and verify the
result through the timeline and invariant report. Do not use cancellation to hide an unexplained test failure.

## Dead-letter recovery

1. Inspect `GET /v1/ops/dead-letters?limit=20`, optionally filtered by `consumer`. Confirm the record belongs to
   the intended consumer and understand the error before acting.
2. Fix the underlying handler/configuration/data-contract issue. This endpoint does not edit malformed raw records.
3. Submit `POST /v1/ops/dead-letters/{dead_letter_id}/redrive` with a new `Idempotency-Key` for this logical action.
   No request body is required. If the response is lost, retry the same request with that same key.
4. Expect 200 only after the inbox-backed effect, resolution and audit transaction commits. Re-driving an already
   resolved letter returns its existing result. A 409 can mean invalid data, an unavailable/unsubscribed consumer,
   or a handler that still fails; do not repeatedly redrive without addressing the cause.
5. Verify the resulting business effect and audit/timeline, not merely the absence of an HTTP error.

## Replay and failed-job retry

`POST /v1/ops/replays` requires OPS or ADMIN authorization, an `Idempotency-Key` and this request shape:

```json
{
  "consumer": "<registered-consumer>",
  "from": "<inclusive-UTC-timestamp>",
  "to": "<exclusive-UTC-timestamp>",
  "reprocess": false
}
```

Choose an inspected consumer and a deliberate time range; these placeholders are not executable inputs.
Acceptance snapshots subscribed, retained outbox events visible at that time, in the half-open interval
`[from, to)`. It is not a query that continues growing while the job runs, and cannot recover source events
already removed by retention.

- HTTP 202 means durable acceptance, not completion. Inspect `GET /v1/ops/replays/{replay_id}` for committed
  progress and failure information. Ensure a worker process is running the replay job.
- Normal replay preserves inbox deduplication. It does not intentionally repeat effects already handled by that
  consumer. `reprocess: true` explicitly changes that behavior; use it only after considering every external
  effect and its own idempotency. Do not delete inbox rows by hand.
- To resume a failed replay after fixing the cause, use `POST /v1/ops/replays/{replay_id}/retry` with a fresh key
  for the retry action. No body is required. It retains committed progress and the remaining snapshot rather than
  re-reading the source outbox. Poll the same replay ID afterward.
- Keep the same key/body when retrying a lost response to the same logical action. `IDEMPOTENCY_KEY_REUSED`
  means the key was reused for different content, not permission to bypass validation.

The [recovery demonstrations](failure-demonstrations.md) exercise broker interruption, replay deduplication,
worker restart, Valkey recovery and node drain on disposable resources. Use those tests to learn the behavior;
do not reproduce their destructive steps against the shared demo stores.

## Startup and local-environment failures

| Symptom | Check and safe next step |
|---|---|
| Port already allocated | Inspect existing containers and choose the documented host-port overrides and a separate project; do not stop another project to free a port |
| Gradle distribution download times out during image build | Check the redirect/network path; the [setup guide](portfolio-completion.md#local-download-caveat) records the development Mac's verified build-only workaround, not a permanent service address |
| Docker socket/host ports fail but processes may still run in Colima | Collect host and in-VM evidence before restarting the VM; do not label it application OOM without container/VM evidence. A Colima restart affects other projects |
| Browser reports `crypto.randomUUID` unavailable | Use HTTPS or loopback HTTP; `http://web` on a Docker bridge is not a secure browser origin |
| Browser page crashes during recording | Inspect the recording container's OOM events and memory allowance; the [recording guide](demo.md) documents the verified Linux image and 2 GiB browser allocation |
| Offline map is blank or missing | Check the `MAPS_DIR` mount, PMTiles range delivery and local sprite/font paths. The compact fixture does not contain OSRM routing data |
| Kafka startup refuses topology | Compare topic partitions, replication, minimum ISR and retention with the documented configuration; do not weaken durability settings merely to get startup green |
| Invariant violations | Preserve the report, relevant timelines and logs, stop adding load to the affected isolated test environment, and investigate before claiming a successful run |

For a suspected OOM, inspect only the relevant containers, for example:

```bash
docker inspect "$CONTAINER" --format '{{.State.Status}} {{.State.OOMKilled}}'
docker events --since 10m --until "$(date +%s)" --filter type=container --filter event=oom
```

Set `CONTAINER` from the inspected project's container list. A client timeout alone is not an OOM diagnosis.

## Shutdown and cleanup

Stop the explicitly named local project while retaining its data. Enable all declared profiles for cleanup so
an optional broker, web or routing service is not accidentally left running:

```bash
docker compose -p "$PROJECT" --profile '*' down
```

Only add `--volumes` for a disposable verification project after confirming its identity and preserving needed
evidence. Keep the original phase-15 database and other project resources intact. Do not use global prune or
unscoped container-stop commands. A controlled node drain has existing integration evidence, but one local
all-role process stopping is still an outage for that local demo, not a zero-downtime cloud deployment.

## Verification status

The API shapes above are checked against the current controllers and OpenAPI contract. This command freshly
passed on 2026-10-13 against revision `d983b6f0c1314fbc9a72667d2fbda5c7a3c6bdfc` in 21s:

```bash
./gradlew test --console=plain \
  --tests '*RideTimelineTests' \
  --tests '*OpsListsTests' \
  --tests '*InvariantReportTests' \
  --tests '*GaugesTests' \
  --tests '*EventRecoveryTests'
```

All 37 cases passed across six suites, including ten architecture checks and sixteen event-recovery cases, with
no failures, errors or skips. The focused run does not replace the full build's contract-coverage gate. Add
`--rerun-tasks` when fresh execution is required; preserve earlier reports before running a focused subset.

Readiness, role inspection, the scripted demo and project-scoped cleanup also have prior isolated-stack evidence
in the [portfolio log](portfolio-completion.md#evidence-log). The test and source evidence supports these
procedures; it does not certify that a particular manual recovery succeeded without checking that environment.