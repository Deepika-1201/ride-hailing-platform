# Ride-Hailing Platform

A ride-hailing and dispatch platform built as a real-time distributed system. Riders request rides, drivers stream their live locations, and the platform matches each request to a nearby driver within seconds, then manages the ride through an explicit state machine until the trip ends.

The focus is the real-time layer: ingesting a continuous stream of driver locations, finding nearby drivers while positions change thousands of times a second, and dispatching safely under concurrency, so that a driver is never assigned to two rides at once.

> **Status:** design approved on 2026-10-02; V1 complete on 2026-10-07. V1 phases 1 (scaffolding), 2 (platform mechanisms), 3 (identity and access), 4 (reference data and profiles), 5 (quotes), 6 (drivers online and location), 7 (booking and dispatch), 8 (ride lifecycle), 9 (payments), 10 (notifications and ratings), 11 (operations) and 12 (V1 complete) are done; V2 phases 13 (Valkey) and 14 (realtime) are done, and phase 15 (simulator and web app) is next. The design covers the requirements baseline, design spikes S-1 to S-3, the high-level design with its three deep dives, the low-level design, the OpenAPI contract and event schemas, the implementation plan, the architecture review, and ADR-001 to ADR-024.

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

## Documentation

| Document | Contents |
|---|---|
| [Requirements](docs/requirements.md) | Scope decisions, functional and non-functional requirements, policies, capacity model, delivery plan by version |
| [High-level design](docs/architecture.md) | Modules and runtime roles, data architecture, key flows, events, APIs, consistency, failures, scaling, security, observability, deployment |
| [Ride lifecycle](docs/ride-lifecycle.md) | Ride, charge and refund state machines; fees; offline commands; races on a ride |
| [Dispatch design](docs/dispatch-design.md) | Driver availability, search tasks, offers, ranking strategies, race scenarios, batch matching |
| [Location system](docs/location-system.md) | Ingestion, ordering, freshness, live index and status mirror, tracking, trip routes, privacy |
| [Low-level design](docs/low-level-design.md) | Code layout, module APIs, database schema, lock order, transaction steps, Valkey scripts, payments, security, realtime protocol, tests |
| [OpenAPI](docs/openapi.yaml) and [schemas](docs/schemas/) | The REST contract; JSON Schemas for every event and WebSocket message |
| [Implementation plan](docs/implementation-plan.md) | Phases from V1 to V8, tests per phase, exit criteria |
| [Architecture review](docs/architecture-review.md) | Findings before implementation, single points of failure, bottlenecks, threats |
| [Decision records](docs/decisions/README.md) | One ADR per significant architecture decision |
| [Design spikes](spikes/README.md) | Throwaway benchmarks behind the ADRs: live location index, database timers, WebSocket cost |
