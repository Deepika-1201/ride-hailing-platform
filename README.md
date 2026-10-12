# Ride-Hailing Platform

A ride-hailing and dispatch platform built as a real-time distributed system. Riders request rides, drivers stream their live locations, and the platform matches each request to a nearby driver within seconds, then manages the ride through an explicit state machine until the trip ends.

The focus is the real-time layer: ingesting a continuous stream of driver locations, finding nearby drivers while positions change thousands of times a second, and dispatching safely under concurrency, so that a driver is never assigned to two rides at once.

> **Status:** design approved on 2026-10-02; V1 complete on 2026-10-07. V1 phases 1 (scaffolding), 2 (platform mechanisms), 3 (identity and access), 4 (reference data and profiles), 5 (quotes), 6 (drivers online and location), 7 (booking and dispatch), 8 (ride lifecycle), 9 (payments), 10 (notifications and ratings), 11 (operations) and 12 (V1 complete) are done; V2 phases 13 (Valkey), 14 (realtime) and 15 (simulator and web app) are done. Phase 16 (laptop tier) awaits its sustained capacity run; phase 17 Kafka delivery, recovery and location streaming still need their final measurements. Phase 18 adds opt-in OSRM routing and time-of-day factors, ETA and weighted ranking, stable percentage experiments, fenced city ownership, and database-backed computed surge without changing those earlier baselines. The design covers the requirements baseline, design spikes S-1 to S-3, the high-level design with its three deep dives, the low-level design, the OpenAPI contract and event schemas, the implementation plan, the architecture review, and ADR-001 to ADR-031.

## Quick start

JDK 25 and Docker are required (on macOS, Colima works).

```bash
./gradlew build            # compile (-Werror), module-boundary and architecture checks, all tests
./gradlew bootTestRun      # run against a throwaway PostgreSQL + PostGIS container: API :8080, management :8081
docker compose up --build  # or PostgreSQL + PostGIS (host port 5434), Valkey (6380) and the app in containers, with JSON logs
./scripts/demo.sh          # with either running: one ride end to end, told step by step (FR-S1)
```

- The demo has a random seeded rider add a mock card and book a trip from Indiranagar to Koramangala, which a random
  seeded driver accepts, starts with the rider's PIN and completes. It then waits for the card to be charged, prints
  the receipt, has both sides rate each other, shows the ride in the rider's history and in the driver's trips with
  its earnings, and prints the ride's timeline as operations see it. It needs `curl` and `python3`.

- `RIDE_ROLES` chooses what a process runs: any of `api`, `realtime`, `dispatch` and `worker` (default: all four).
- `RIDE_LOCATION_STORE` chooses where the live index, rate limits and WebSocket tickets live: `memory` (the default,
  for one process) or `valkey` at `RIDE_VALKEY_URI`, which Compose uses and any split of roles needs.
- Both local runs use the `local` profile: the sign-in code is always `123456`, and seeds load Bengaluru (service
  area, airport and station areas, four categories, fare, fee and surge rules), 2,000 verified drivers with vehicles
  (`+917000000001` to `+917000002000`), 500 riders (`+918000000001` to `+918000000500`), operations `+919000000001`
  and admin `+919000000002`. Against a running stack, `scripts/sign-in-smoke.sh` signs in as the admin, refreshes and
  logs out, `scripts/reference-data-smoke.sh` calls every admin and profile endpoint as the admin, rider 1 and
  driver 1, `scripts/quote-smoke.sh` quotes a Bengaluru trip as rider 2, `scripts/driver-online-smoke.sh` takes
  driver 3 online, sends a location and finds them as the pickup ETA of rider 3's quote, and
  `scripts/booking-smoke.sh` has a random seeded rider book, cancel and book again while a random seeded driver
  polls for the offer and accepts it, then arrives, is refused a wrong PIN, starts the trip with the rider's PIN and
  completes it. `scripts/payment-smoke.sh` has a random seeded rider pay by a mock card that declines: the fare
  becomes dues, which refuse the next booking until the rider pays them with a card that succeeds; operations then
  refund part of the charge, and the driver's earnings show the trip. `scripts/rating-smoke.sh` has a random seeded
  rider and driver take a ride and rate each other, is refused a second rating, and sees the ride's notifications
  sent. `scripts/ops-smoke.sh` signs in as the seeded operations user: a random seeded driver is suspended (taken
  offline and refused going online) and reinstated, a seeded rider's ride shows in the active rides with its
  timeline, and operations cancel it. The picks are random so that a rerun after a failed run, which can leave a
  ride open, starts afresh.
- Payments go to an in-process mock provider (LLD §11.9). Card tokens choose its answer: `tok_ok`, `tok_decline`,
  `tok_timeout_failed`, `tok_timeout_succeeded` and `tok_webhook_only`; other tokens draw from its decline and timeout
  rates. It posts signed webhooks to the application, late, duplicated or ahead of its answer.
- Notifications go to a log-only push provider: each sent push logs its kind and recipient, never its payload.
- The concurrency races repeat 200 times each (LLD §17.2); `./gradlew test -PraceRepetitions=20` runs them quicker,
  and `./gradlew test -Ptags=race -PraceRepetitions=1000` runs only the race suite, longer.
- A full test run also checks contract coverage (LLD §17.1): every implemented endpoint has answered a success,
  every event type has been produced, and all eight WebSocket server message types have been checked against
  `openapi.yaml` or their schemas. Runs with `--tests` or `-Ptags` skip it.
- Tests use Testcontainers. With Colima, point it at Colima's socket once:
  `printf 'docker.host=unix://%s/.colima/default/docker.sock\n' "$HOME" > ~/.testcontainers.properties`
- The test run also writes module diagrams (PlantUML) to `build/spring-modulith-docs`.

## Kafka development

Domain-event publication, inbox-backed consumers and durable dead-letter mirroring can run through Kafka.
Staff can redrive failures and request durable event replays. This is opt-in development work; phase 17 is not
complete. The best-effort location stream is implemented; sustained S-4 throughput measurements and remaining phase gates are still pending.
Normal startup and the V2 capacity baseline retain in-process delivery.

Stop all workers before changing transport mode, and configure every worker consistently. To run the local
single-broker stack:

```bash
export RIDE_KAFKA_ENABLED=true
export RIDE_KAFKA_MANAGE_TOPICS=true
docker compose --profile kafka up --build --detach
```

The broker has its own named volume and is available to containers at `kafka:9092` and to host tools at
`localhost:19092`. Set `KAFKA_PORT` to change the host port. A host-run JVM uses `RIDE_KAFKA_BROKERS=localhost:19092`
with its existing database settings. The application waits for the local broker's health check when that profile
is active; API-only processes do not start Kafka consumers.

Topic management creates missing event, location and per-consumer dead-letter topics, then validates the existing topology.
It does not silently alter partitions, replication or retention. Outside this local setup, provision the declared
topics first and leave `RIDE_KAFKA_MANAGE_TOPICS=false`. Local defaults are three event partitions, one dead-letter
partition, replication factor one and minimum ISR one; they are not a durability or capacity certification.

Rows already marked published by in-process delivery are not automatically backfilled into Kafka. A broker outage
leaves new outbox rows pending; it never switches delivery back to in-process. Keep the transport setting consistent
when recreating application containers. The separate S-4 spike infrastructure remains throwaway and should not
carry application traffic.

### Location stream

Kafka mode publishes applied live and REST positions to `location.updates`, keyed by driver ID, using the
[versioned location schema](docs/schemas/location/update.v1.json). Stale, offline and rejected updates are not
published. Quality flags describe the original fix; a flagged fix need not move the usable live position.
Applied offline replays carry `replay: true`; stale replays still reach local trip storage independently.

Publication uses a bounded background queue, `acks=1`, 20ms linger and no durable retries. A broker outage,
serialization error or full queue may drop stream records without blocking live ingestion or trip buffering.
Consumers must tolerate gaps and use driver sequences to discard duplicate or obsolete records. This does not
replace domain-event outbox delivery or certify a capacity target.

Defaults under `ride.kafka.stream` are `queue-capacity=1024`, `max-record-bytes=2048`, `buffer-bytes=1048576`,
`send-timeout=2s` and `shutdown-timeout=1s`. The producer is lazy; API/realtime processes do not need a successful
broker connection to start accepting locations. Provision topics before starting these roles alone. Worker
preflight requires exactly 24-hour configured retention for `location.updates`, rejecting longer or unlimited
retention without silently changing it. Kafka segment deletion scheduling still applies.

`event_stream_records_total{topic,outcome}` counts queued, published, failed, queue-full, oversized and shutdown
outcomes; `event_stream_queue_size` exposes pending work and `location_stream_failures_total` counts unexpected
publication-hook failures. All counters are registered at zero. See [ADR-026](docs/decisions/ADR-026-best-effort-location-stream.md).

### Event recovery

Operations and admin accounts use these endpoints; every `POST` requires an `Idempotency-Key`:

| Endpoint | Purpose |
|---|---|
| `GET /v1/ops/dead-letters?consumer=notification.rides` | Paginated failure metadata, without raw event payloads |
| `POST /v1/ops/dead-letters/{id}/redrive` | Revalidate the original record and invoke its consumer through the inbox |
| `POST /v1/ops/replays` | Accept `{consumer, from, to, reprocess}` and return a durable replay ID |
| `GET /v1/ops/replays/{id}` | Read `RUNNING`, `DONE` or `FAILED`, committed progress and fixed snapshot size |
| `POST /v1/ops/replays/{id}/retry` | Resume a failed replay from its remaining snapshot after fixing the consumer |

Replays select retained events in `[from, to)` at acceptance, then process them in outbox ID order. Later events
are not added, and outbox retention cannot remove the accepted snapshot. Worker restarts resume committed
progress. Normal replay preserves inbox deduplication; `reprocess: true` deliberately invokes already-handled
events again and should be used only for a reviewed consumer fix. Retrying a failed replay does not repeat
committed batches or move live Kafka offsets. Staff recovery commands are audited.

Mirror writes commit before Kafka offsets. Database outages leave the mirror retrying and replay work pending;
malformed dead letters remain stored and unresolved. Resolved letters and finished replay records use
`ride.retention.failed-deliveries` (14 days by default); unresolved letters and running jobs are retained.
Metrics include `dead_letters_pending`, `dead_letters_redriven_total`, `event_replays_running`,
`event_replays_failures_total`, `event_replays_completed_total` and `event_replay_events_total`.

## OSRM routing development

The default routing provider remains the deterministic mock. Phase 18 preparation can use self-hosted OSRM for
explicitly selected origin cities, with road distances and durations flowing into quotes and tracking ETAs.
Traffic adjustment and computed surge remain disabled by default, and this is not a capacity result. ETA-ranked dispatch is
available separately through the category's ranker setting; the seeded/default setting stays `nearest`.

```bash
./scripts/setup-maps.sh
RIDE_ROUTING_OSRM_ENABLED=true RIDE_ROUTING_OSRM_CITIES=blr \
  docker compose --profile routing up --build --detach
```

For a host-run JVM with its existing database setup, use `RIDE_ROUTING_OSRM_ENABLED=true`,
`RIDE_ROUTING_OSRM_CITIES=blr` and `RIDE_ROUTING_OSRM_BASE_URL=http://localhost:5000`. Configure these settings
consistently on roles that calculate routes. Enabling OSRM requires a nonempty city list; other cities keep the
mock. Merely starting the `routing` profile does not change the application's routing provider.

The complete HTTP response has a 300ms default deadline and a 64KiB size limit. Provider errors and malformed
responses use the mock with stored quote source `MOCK_FALLBACK`; OSRM's explicit `NoRoute` still produces
`422 ROUTE_NOT_FOUND`. OSRM's static car profile is not live traffic data.

With Valkey enabled, valid road results are cached for ten minutes by ordered H3 resolution-9 cell pairs and
OSRM endpoint. Nearby endpoints may share an approximate route. Each cache read/write waits at most 20ms;
cache failures degrade to an uncached call. Fallback and no-route answers are never cached. Changing map data
at the same endpoint requires clearing that routing cache namespace or waiting for expiry. Without Valkey,
OSRM works uncached. `routing_requests_total{provider,outcome}` and `routing_cache_total{outcome}` expose
provider outcomes and cache behavior on the management endpoint.

The settings are `ride.routing.osrm.enabled`, `base-url`, `cities`, `timeout`, `cache-ttl` and `cache-timeout`.
See [ADR-013](docs/decisions/ADR-013-routing-provider.md) for the decision and limits. Phase 16 measurements
must keep this opt-in disabled so they retain the declared V2 baseline.

### Time-of-day factors

Optional factors model expected slow periods, not live traffic. Enable OSRM for the same cities, then configure
explicit windows consistently across roles that calculate routes. The following is an illustrative assumption,
not a tuned Bengaluru profile:

```yaml
ride:
  routing:
    osrm:
      enabled: true
      cities: [blr]
    traffic:
      enabled: true
      cities:
        blr:
          - zone-id: "*"
            days-of-week: [1, 2, 3, 4, 5]
            start-local: "17:00"
            end-local: "20:00"
            multiplier: 1.50
```

`zone-id` accepts `*`, an H3 resolution-7 cell or `area:<code>`. Only active special areas match. Windows use the
origin city's local time; starts are inclusive, ends exclusive, and midnight windows belong to the starting day.
The highest matching factor wins, within 1.00..3.00. Factors apply to duration only, not distance or mock fallback.

The ten-minute cache always holds raw OSRM data. Each request applies its current factor after reading the cache.
Quotes record `OSRM_ADJUSTED`; matrix rows use their own origin's factor and retain fractional seconds and
unreachable cells. ETA and weighted decision candidates include `traffic_multiplier` when the matrix is adjusted.
Overflow preserves the entire raw result and increments `routing_traffic_total{kind,outcome="overflow"}` rather
than producing invalid or partly adjusted times. Normal outcomes are `adjusted` and `unchanged`.

These static factors still require outcome and latency measurements. See [ADR-031](docs/decisions/ADR-031-time-of-day-routing-factors.md).

### ETA-ranked dispatch

With OSRM enabled for the city, set `ranker` to `eta` using the existing
`PUT /v1/admin/cities/{city_id}/categories/{category}` endpoint, including the category's other settings and current
version. No default or existing category is switched automatically.

An attempt sends one directed OSRM table request for the ten nearest eligible drivers to the pickup. It ranks
reachable drivers by fractional ETA seconds, with deterministic distance/freshness/ID tie-breaking. Unreachable
pairs are excluded for that attempt; candidates outside the ten-driver cap are not backfilled. If the table is
unavailable, malformed or times out, the existing distance order is used. Cities without OSRM also use this
distance fallback when configured as `eta`.

The decision timeline retains strategy `eta` and reports `score_unit`, optional `ranking_fallback`, and counts of
unreachable and capped candidates. Matrix requests are uncached and use the same deadline and response cap as
route requests. Reservation remains conditional, so a driver becoming unavailable during the routing call cannot
be assigned on stale data. `routing_matrix_requests_total` and `dispatch_ranking_total` expose table and ranker
outcomes. These checks establish behavior and concurrency safety, not improved pickup-time or capacity targets.

### Weighted dispatch

Set the category's `ranker` to `weighted` through the same versioned admin endpoint to combine road ETA with
acceptance history, continuous idle time and heading alignment. OSRM must be enabled for that city to supply
road times; otherwise the policy explicitly falls back to distance. The default remains `nearest`.

The optional `ranking_weights` object has four required values when supplied:

```json
{"eta": 1, "acceptance": 60, "idle": 2, "heading": 30}
```

These are initial experimental coefficients, not tuned performance claims. Omitted or null weights preserve
existing values; new categories use the defaults. Changes require the category's current version and are audited.

The policy uses the same ten-driver shortlist as ETA ranking, an 8/10 acceptance prior, at most thirty minutes
of idle credit, and neutral heading penalty when usable motion is missing or too slow. The decision's
`weighted_score` records its inputs, effective weights and component costs so the result is explainable without
adding raw coordinates to the log. `dispatch_ranking_total{strategy="weighted",outcome}` reports weighted
executions and fallbacks. See [ADR-027](docs/decisions/ADR-027-weighted-dispatch-scoring.md) for the exact rules.
Outcome comparisons and the batch-matching experiment remain pending.

### Ranking experiments

The versioned category endpoint accepts an optional `ranking_experiment` alongside its existing `ranker` and
`ranking_weights`. The category ranker is the control, and this object configures the treatment:

```json
{"id": "eta-trial", "ranker": "eta", "percentage": 30}
```

With category `ranker=nearest`, approximately 30% of new searches receive ETA ranking; the rest use nearest.
Allocation hashes the ride ID, city, category and experiment ID into a stable bucket. It is not an exact quota.
Increasing the percentage retains nested cohorts; changing the experiment ID changes the buckets.

The first committed attempt stores its selected strategy, category version, weights and experiment provenance.
Retries, including index failures and node restarts, keep that policy even if the category changes. Decisions
include it as `detail.ranking_assignment`; existing strategy columns, offer events and fallback fields remain
accurate. A new search task after acceptance takes a new snapshot. Analyze those search lifetimes separately.

Omitted or null experiments preserve the current setting on update. Set `percentage` to `0` to disable treatment
for new assignments; `100` assigns all of them to treatment. This does not override active searches and is not an
emergency kill switch. No experiment is configured by default, and ETA/weighted treatments still need OSRM for
road times. See [ADR-030](docs/decisions/ADR-030-stable-ranking-experiments.md). Outcome measurements remain pending.

### City ownership

City ownership is an opt-in foundation for singleton surge and future batch-matching jobs. It does not change
sequential ride searches or enable computed surge by itself. Enable it on processes with the `dispatch` role, using an explicit
city list:

```yaml
ride:
  dispatch:
    city-ownership:
      enabled: true
      cities: [blr, mum]
      ttl: 10s
      renew-every: 3s
```

Nodes compete independently for each `city-owner:<city>` PostgreSQL lease. Ownership is first-acquirer, not load
balanced. `CityOwnership.owned()` provides cached claims for preparing work; `runIfOwned(claim, databaseWork)`
checks the captured holder/token under a lease-row lock and commits database work only while that claim remains
valid. Expiry rolls back pending changes. Remote calls and expensive preparation must happen outside this guard.

Renewal errors discard local claims. Shutdown rejects new work and attempts token-scoped release within five
seconds; unfinished releases expire naturally. Restart obtains new tokens. `ride.workers.autostart=false`
suppresses the automatic loop. Defaults leave ownership disabled, so Kafka is not required for it.

`dispatch_city_ownership_total{outcome="acquired|renewed|lost|error"}` records lease maintenance, and
`dispatch_city_ownership_held` counts cached local claims, not independently verified database owners.
See [ADR-028](docs/decisions/ADR-028-city-owned-database-work.md) for the transaction boundary and failure rules.

### Computed surge

Computed surge is disabled by default. Enable it with explicit per-city thresholds, and configure city ownership
on dispatch processes for every city they compute. API processes need the same pricing configuration, but do not
need the ownership coordinator. For example, the following thresholds are experimental, not tuned recommendations:

```yaml
ride:
  dispatch:
    city-ownership:
      enabled: true
      cities: [blr]
  pricing:
    computed-surge:
      enabled: true
      max-drivers: 5000
      cities:
        blr:
          - {ratio-from: 0, multiplier: 1.00}
          - {ratio-from: 1, multiplier: 1.50}
          - {ratio-from: 2, multiplier: 2.00}
```

Every minute, the city's dispatch owner measures five-minute booking demand plus 0.20 times quote demand and
compares it with fresh available drivers plus half the neighboring H3-ring supply. Booking demand uses quote
consumption in the booking transaction, so failed or repeated bookings do not count twice. Special areas use
local supply only. Thresholds are inclusive, and the multiplier moves by at most 0.20 per committed update,
within 1.00..2.00. A persisted schedule prevents retries or ownership changes from accelerating that adjustment.

Supply reads reject more than `max-drivers` positioned online drivers (including busy or stale drivers), with a
hard maximum of 10,000. Zone evaluation is capped at 10,000. The job waits for a live-index epoch at least one
minute old, rechecks it after sampling, and rejects observations older than ten seconds at publication. Failed,
incomplete or reset-index samples do not publish zero supply. These are bounded cross-store samples, not a global
atomic snapshot. `ride.workers.autostart=false` also suppresses the surge poller.

Fresh computed values take precedence over rules, even when lower. After two minutes from observation, or when
a zone has no computed value, quotes fall back to the existing rules and then 1.00. Issued fares never change.
Raw counts, weighted inputs, targets, thresholds and ownership tokens are retained in history for seven days.
`pricing_surge_cycles_total{outcome}` reports publication and skipped/failed cycles;
`pricing_surge_selection_total{source}` counts quote selection as `computed`, `rule` or `none`.

The initial read path is an indexed PostgreSQL lookup. Valkey surge caching and performance comparisons remain
deferred, and this is not capacity or improved-outcome certification. See [ADR-029](docs/decisions/ADR-029-computed-surge.md).

## Simulator and web app

Phase 15 adds the Go simulator, an operations map, and Rider and Driver screens. The host toolchains are Go 1.27
and Node.js 22 or later; container builds provide their own toolchains.

```bash
./scripts/setup-maps.sh
docker compose up --build --detach
docker compose --profile simulator up --build --detach --no-deps web
docker compose --profile simulator run --build --rm --no-deps simulator
```

Open <http://localhost:8088>. The default manual accounts are rider 9001 and MINI driver 1993; open their screens
in separate tabs. Set the rider's pickup and drop-off on the map, take the driver online, then accept the offer,
arrive, enter the rider's PIN, start and complete the trip. The CI simulator uses driver and rider numbers
101-140; avoid sharing an identity with a running scenario.

The default simulator command replays the committed routes without OSRM. To run road-following agents and record
new routes, prepare the maps once and start the optional routing service:

```bash
docker compose --profile routing up --detach osrm
docker compose --profile simulator run --rm simulator run \
  -api http://app:8080 -ws ws://app:8080/ws \
  -scenario scenarios/ci.yaml -osrm http://osrm:5000 \
  -record state/routes.json -tokens state/tokens.json -report state/report.json
docker compose --profile simulator run --rm --no-deps --entrypoint cat simulator state/report.json
```

Runs report percentiles, rates, faults, retries and all eight invariant checks. Missing recorded routes use
straight lines and are counted as `route_misses`; replay is a functional smoke test, not a capacity result.
The `ci` scenario allows four minutes of demand plus seven minutes to finish rides. Other supplied scenarios are
weekday peak, airport wave, stadium exit and chaos. Cached tokens and reports stay in a named volume; tokens are
credentials and must not be committed.

For web development against the API on port 8080, run these commands from `web/`:

```bash
npm ci
npm run dev
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

The dev server is <http://localhost:5173>. Set `WEB_BASE_URL=http://localhost:8088` to run the browser test against
nginx instead. Map files, fonts and sprites are served locally; no map keys or external requests are needed at
runtime. The setup script downloads the full map into ignored `.maps/`; `web/testdata/bengaluru.pmtiles` is only
a compact zoom-12 CI fixture. CI runs the simulator without OSRM and uploads its report and browser screenshots.

Map data is derived from [OpenStreetMap](https://www.openstreetmap.org/copyright), distributed under the
[ODbL](https://opendatacommons.org/licenses/odbl/), via Geofabrik and Protomaps. The committed route recording and
map fixture were produced from the 2026-10-09 extracts. Sprites come from
[Protomaps basemaps-assets](https://github.com/protomaps/basemaps-assets), derived from MIT-licensed Mapzen icons;
the web app includes the required sprite and IBM Plex font notices in its static assets.

## Documentation

| Document | Contents |
|---|---|
| [Requirements](docs/requirements.md) | Scope decisions, functional and non-functional requirements, policies, capacity model, delivery plan by version |
| [High-level design](docs/architecture.md) | Modules and runtime roles, data architecture, key flows, events, APIs, consistency, failures, scaling, security, observability, deployment |
| [Ride lifecycle](docs/ride-lifecycle.md) | Ride, charge and refund state machines; fees; offline commands; races on a ride |
| [Dispatch design](docs/dispatch-design.md) | Driver availability, search tasks, offers, ranking strategies, race scenarios, batch matching |
| [Location system](docs/location-system.md) | Ingestion, ordering, freshness, live index and status mirror, tracking, trip routes, privacy |
| [Low-level design](docs/low-level-design.md) | Code layout, module APIs, database schema, lock order, transaction steps, Valkey scripts, payments, security, realtime protocol, tests |
| [OpenAPI](docs/openapi.yaml) and [schemas](docs/schemas/) | The REST contract; JSON Schemas for domain events, location stream records and WebSocket messages |
| [Implementation plan](docs/implementation-plan.md) | Phases from V1 to V8, tests per phase, exit criteria |
| [Capacity report](docs/capacity-report.md) | Laptop measurement setup, interrupted attempt, collector safeguards and remaining certification evidence |
| [Architecture review](docs/architecture-review.md) | Findings before implementation, single points of failure, bottlenecks, threats |
| [Decision records](docs/decisions/README.md) | One ADR per significant architecture decision |
| [Design spikes](spikes/README.md) | Throwaway benchmarks behind the ADRs: live location index, database timers, WebSocket cost |
