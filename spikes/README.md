# Design spikes

Throwaway experiments that answer the open questions in [requirements §12](../docs/requirements.md#12-open-questions-for-design-spikes) before the design is written. **Not product code**: nothing here is reused by the application, and it doesn't follow the product's coding standards. The findings are recorded in [results/](results/) and in the ADRs that cite them.

| Spike | Question | Result |
|---|---|---|
| S-1 | Where should live driver positions live: Valkey GEO, Valkey with H3 cells, or PostGIS? | [results/s1-live-index.md](results/s1-live-index.md) |
| S-2 | Can a PostgreSQL table fire offer timeouts within ~1 s? | [results/s2-timers.md](results/s2-timers.md) |
| S-3 | How much memory and CPU does a WebSocket connection cost on Tomcat vs Netty? | [results/s3-websockets.md](results/s3-websockets.md) |
| S-4 | Outbox relay throughput and per-aggregate ordering through failover | Finite experiment, broker-pause and same-storage restart recovery tested; sustained throughput and replicated failover measurements remain pending in phase 17 |

## What's here

| Path | Contents |
|---|---|
| [compose.yaml](compose.yaml) | Valkey 9, PostgreSQL 18 with PostGIS 3.6, the optional S-4 Kafka 4.3.1 broker, and the spike images |
| [bench/](bench/) | Go load generator: `geo` (S-1), `timers` (S-2), `wsclient` (S-3), and `outbox-run`, `outbox-verify`, `outbox-capture` (S-4) |
| [ws-server/](ws-server/) | Spring Boot 4.1 WebSocket server that runs on either Tomcat or Netty (S-3) |
| [run.sh](run.sh) | Runs a whole spike and saves raw output to `results/raw/` |

## Running

Needs Docker with about 4 GB of memory (Colima on the development Mac) and, for S-3, JDK 25 to build the server jar.

```sh
cd spikes
(cd ws-server && ./gradlew -q bootJar)   # S-3 only
COMPOSE_PROFILES=tools,ws docker compose build
./run.sh s1    # ~10 min
./run.sh s2    # ~6 min
./run.sh s3    # ~5 min
docker compose down -v
```

The S-1 to S-3 runs generate load inside the same Colima VM (4 vCPUs) as the servers, so absolute latencies include client contention. Compare approaches with each other, and use the server-side costs (CPU per operation, WAL bytes, time inside the server) for capacity planning.

## S-4 trace verification

Prepare an expected-event ledger and a received-event trace as JSON Lines. Each record contains `event_id`,
`aggregate_type`, `aggregate_id` and integer `aggregate_version`; additional envelope fields are ignored.

```sh
cd bench
go run . outbox-verify -expected expected.jsonl -received received.jsonl
go test ./...
```

The ledger must be nonempty and contain unique event IDs. The verifier reports missing and unexpected IDs,
identity/version mismatches, duplicate deliveries, and decreasing versions among first deliveries of an aggregate.
Exact redelivery is allowed, including an older event delivered again after a newer one. Equal aggregate versions
are allowed because one state change may emit multiple events. Distinct aggregate types do not share ordering state.
It checks identity and order, not payload schemas, and prints a JSON summary with at most 20 example violations.
Invalid input or a violated delivery invariant exits nonzero. A passing trace is not a throughput measurement or
proof that the actual Kafka relay is correct; those require the phase 17 failover experiment.

## S-4 batch runner

[outbox_relay.go](bench/outbox_relay.go) models the conservative baseline in [LLD §19.1](../docs/low-level-design.md#191-v3-kafka).
It checks lease ownership before reading and between sends, waits for each acknowledgement in ID order, then
marks the full batch published. The store must atomically check the holder, fencing token and unexpired lease
when marking; a publisher must return success only after acknowledgement. Both adapters are implemented below.
Every batch reads pending rows again, so a late commit below a previous batch's IDs is not skipped.

```sh
cd bench
go test -count=1 -race -run 'TestOutbox(Relay|Trace)' ./...
```

Deterministic tests inject send and acknowledgement failures, lease takeover between events and before marking,
a new token for the same holder, failed database marking and cancellation. A successor replays the unpublished
batch, and the trace verifier checks that the duplicates introduce neither loss nor first-delivery reordering.
These are control-flow tests with an in-memory store and publisher, not PostgreSQL fencing tests, Kafka failover
evidence or throughput results. The product's V2 runtime is unchanged.

## S-4 PostgreSQL adapter

[outbox_postgres.go](bench/outbox_postgres.go) implements the store against PostgreSQL, using only a separate
`spike_s4_*` schema. Lease operations serialize on the lease row. Marking checks holder, token and database-clock
expiry in the update, then rechecks expiry and the affected row count before committing. A partial batch rolls back.

To run the database tests against a disposable PostgreSQL database:

```sh
cd bench
SPIKE_TEST_PG='postgres://spike:spike@localhost:5432/spike?sslmode=disable' go test -count=1 -race -run '^TestPostgresOutbox' ./...
```

The test account needs schema-creation privileges. Each test creates a unique schema and drops only that schema
on cleanup; it never uses product tables. Tests cover expired and superseded leases, same-holder token changes,
a mark blocked behind takeover, partial-update rollback, expiry injected during marking, lost-ack replay through
the real store, late commits and rolled-back events. The publisher is still a test double: these do not establish
Kafka behavior or throughput. CI supplies PostgreSQL 18.6 and requires `SPIKE_TEST_PG`; outside CI, database tests
explicitly skip when it is unset.

## S-4 Kafka adapter and capture

[outbox_kafka.go](bench/outbox_kafka.go) uses `franz-go` with idempotent production, `acks=all` and zstd.
The synchronous baseline waits for each acknowledgement and flushes immediately despite the configured 5 ms
linger. A hard publish deadline closes the producer; an ambiguous send leaves the database batch unpublished,
and replay must use a new producer. Only `spike-s4-*` topic names are accepted. This is local plaintext spike
infrastructure, not production Kafka configuration.

The optional broker listens on loopback port 29092, has a 256 MiB heap and a 1 GiB container limit. Start only
that service, then run the tests with an existing disposable PostgreSQL database:

```sh
cd spikes
docker compose -p ride-s4-checkpoint --profile s4 up --detach --wait kafka
cd bench
SPIKE_TEST_PG='postgres://spike:spike@localhost:5432/spike?sslmode=disable' \
	SPIKE_TEST_KAFKA=localhost:29092 \
	SPIKE_TEST_KAFKA_CONTAINER="$(docker compose -p ride-s4-checkpoint -f ../compose.yaml --profile s4 ps -q kafka)" \
	go test -count=1 -race ./...
cd ..
docker compose -p ride-s4-checkpoint --profile s4 down
```

Tests create and delete their own three-partition topics. They check an empty capture, exact envelope preservation
across multiple partitions, and replay by a second producer after the first acknowledges a batch but fails before
database marking. They also delete a topic's retained prefix and prove capture rejects it. CI supplies both stores
and requires `SPIKE_TEST_PG`, `SPIKE_TEST_KAFKA` and `SPIKE_TEST_KAFKA_CONTAINER`. Local broker tests explicitly
skip if Kafka is not configured; the outage tests also skip when their container target is not supplied.

The broker-outage regressions require the Docker CLI and accept only a running, unpaused Kafka 4.3.1 container
with a health check and label `com.ridehailing.spike=s4`. They resolve the immutable ID before taking action.
After one event is acknowledged, they pause or stop Kafka while PostgreSQL remains available. The next send
reaches its one-second deadline, the test's 250 ms lease expires naturally, and every row remains pending.
After recovery, a new producer and fenced lease replay the batch. All three events must arrive in first-delivery
order. The pause case permits one or two duplicates because the unacknowledged send may still have reached Kafka.

The restart case uses Docker stop/start on the same container and storage, with a five-second stop grace period.
It verifies the container really stopped and its start timestamp changed, waits for broker health and topic
readiness, then captures the acknowledged prefix before replay. That prefix must already contain the first event
exactly once; replay cannot conceal lost broker data. Final recovery has exactly one duplicate.

Cleanup resumes or starts the broker and waits for readiness before deleting test resources, including after
assertion failures. Killing the test process can bypass cleanup; restore the dedicated test broker before further
testing or removing the stack. These tests use one broker with replication factor one. They do not establish
host-power-loss, disk-loss, leader-election, multi-replica durability or throughput guarantees.

After an experiment stops publishing, capture its fresh, noncompacted, nontransactional topic:

```sh
cd bench
go run . outbox-capture -brokers localhost:29092 -topic spike-s4-example -output received.jsonl
go run . outbox-verify -expected expected.jsonl -received received.jsonl
```

Capture snapshots each partition's end offset and writes the offset-zero prefix as JSON Lines, without joining or
committing a consumer group. It rejects missing offsets, truncation, malformed envelopes and inconsistent headers,
and ignores records beyond the snapshot. Do not recreate or repartition the topic during capture. Its JSON summary
on stdout includes the end offsets and record count. Output files are private and never overwritten; a failed
capture exits nonzero and leaves its partial trace for diagnosis, not as passing evidence. The verifier checks event
identity and first-delivery order, not payload schemas; the round-trip test checks full envelope preservation.

## S-4 finite experiment

[outbox_experiment.go](bench/outbox_experiment.go) connects the adapters into a finite, preloaded-backlog run.
It creates its own schema and three-partition, replication-one topic, commits synthetic events, exports the
expected ledger from those committed rows, drains through one leased relay, captures Kafka's output and verifies it.
Only the schema and topic created by the run are cleaned up. Database and broker failures stop the run rather
than being silently retried by the experiment driver.

With Kafka and a disposable PostgreSQL database running:

```sh
cd spikes/bench
SPIKE_PG='postgres://spike:spike@localhost:5432/spike?sslmode=disable' \
	go run -buildvcs=true . outbox-run -brokers localhost:29092 \
	-events 1000 -aggregates 100 -batch 500 -payload-bytes 256 \
	-output "../../build/spikes/s4-$(date -u +%Y%m%dT%H%M%SZ)"
```

For the workspace's existing local PostgreSQL service, `SPIKE_PG` can point to
`postgres://ridehailing:ridehailing@localhost:5434/postgres?sslmode=disable`. The experiment uses only its fresh
`spike_s4_*` schema in that database, never the application's tables.

The command requires a new output directory and creates it with mode 0700. Artifacts use mode 0600:

| Artifact | Contents |
|---|---|
| `expected.jsonl` | Ledger exported after all synthetic events commit |
| `received.jsonl` | Kafka trace captured after the relay finishes |
| `report.json` | Configuration, run IDs, available build revision, stages, counts, timings and verification |

On failure, the command exits nonzero and retains the report and any artifacts already produced. Connection URLs
and credentials are not written to the report. Cleanup failures also fail the command. A process kill or machine
shutdown can prevent cleanup or report writing; the private artifacts are not a substitute for a successful exit.
Build with `-buildvcs=true` from the repository to include the source revision and dirty-worktree flag in the report.

The default overall timeout is two minutes, excluding bounded cleanup; `-publish-timeout` defaults to five seconds.
The rate uses wall-clock time for the drain only, while the report also records its monotonic duration. The drain
includes the producer's first connection and metadata work. This is a cold, synchronous backlog-drain result,
not a sustained ingestion rate, a broker-outage experiment, a replica-durability test or a product capacity claim.
Use AC power and an open lid for larger measurements, and retain the hardware/service context alongside the artifacts.
CI runs a small end-to-end experiment and checks retained evidence after a publish deadline; no load threshold is
inferred from those smoke tests.
