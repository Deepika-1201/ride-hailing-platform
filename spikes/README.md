# Design spikes

Throwaway experiments that answer the open questions in [requirements §12](../docs/requirements.md#12-open-questions-for-design-spikes) before the design is written. **Not product code**: nothing here is reused by the application, and it doesn't follow the product's coding standards. The findings are recorded in [results/](results/) and in the ADRs that cite them.

| Spike | Question | Result |
|---|---|---|
| S-1 | Where should live driver positions live: Valkey GEO, Valkey with H3 cells, or PostGIS? | [results/s1-live-index.md](results/s1-live-index.md) |
| S-2 | Can a PostgreSQL table fire offer timeouts within ~1 s? | [results/s2-timers.md](results/s2-timers.md) |
| S-3 | How much memory and CPU does a WebSocket connection cost on Tomcat vs Netty? | [results/s3-websockets.md](results/s3-websockets.md) |
| S-4 | Outbox relay throughput and per-aggregate ordering through failover | Offline trace verifier prepared; broker measurements remain pending in phase 17 |

## What's here

| Path | Contents |
|---|---|
| [compose.yaml](compose.yaml) | Valkey 9, PostgreSQL 18 with PostGIS 3.6, and the spike images |
| [bench/](bench/) | Go load generator: `geo` (S-1), `timers` (S-2) and `wsclient` (S-3) |
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

All load is generated inside the same Colima VM (4 vCPUs) as the servers, so absolute latencies include client contention. Compare approaches with each other, and use the server-side costs (CPU per operation, WAL bytes, time inside the server) for capacity planning.

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
