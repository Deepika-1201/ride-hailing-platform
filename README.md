# Ride-Hailing Platform

A ride-hailing and dispatch platform built as a real-time distributed system. Riders request rides, drivers stream their live locations, and the platform matches each request to a nearby driver within seconds, then manages the ride through an explicit state machine until the trip ends.

The focus is the real-time layer: ingesting a continuous stream of driver locations, finding nearby drivers while positions change thousands of times a second, and dispatching safely under concurrency, so that a driver is never assigned to two rides at once.

> **Status:** design approved on 2026-10-02. V1 phases 1 (scaffolding), 2 (platform mechanisms), 3 (identity and access), 4 (reference data and profiles), 5 (quotes), 6 (drivers online and location), 7 (booking and dispatch) and 8 (ride lifecycle) are done; phase 9 (payments) is next. The design covers the requirements baseline, design spikes S-1 to S-3, the high-level design with its three deep dives, the low-level design, the OpenAPI contract and event schemas, the implementation plan, the architecture review, and ADR-001 to ADR-024.

## Quick start

JDK 25 and Docker are required (on macOS, Colima works).

```bash
./gradlew build            # compile (-Werror), module-boundary and architecture checks, all tests
./gradlew bootTestRun      # run against a throwaway PostgreSQL + PostGIS container: API :8080, management :8081
docker compose up --build  # or PostgreSQL + PostGIS (host port 5434) and the app in containers, with JSON logs
```

- `RIDE_ROLES` chooses what a process runs: any of `api`, `realtime`, `dispatch` and `worker` (default: all four).
- Both local runs use the `local` profile: the sign-in code is always `123456`, and seeds load Bengaluru (service
  area, airport and station areas, four categories, fare, fee and surge rules), 2,000 verified drivers with vehicles
  (`+917000000001` to `+917000002000`), 500 riders (`+918000000001` to `+918000000500`), operations `+919000000001`
  and admin `+919000000002`. Against a running stack, `scripts/sign-in-smoke.sh` signs in as the admin, refreshes and
  logs out, `scripts/reference-data-smoke.sh` calls every admin and profile endpoint as the admin, rider 1 and
  driver 1, `scripts/quote-smoke.sh` quotes a Bengaluru trip as rider 2, `scripts/driver-online-smoke.sh` takes
  driver 3 online, sends a location and finds them as the pickup ETA of rider 3's quote, and
  `scripts/booking-smoke.sh` has a random seeded rider book, cancel and book again while a random seeded driver
  polls for the offer and accepts it, then arrives, is refused a wrong PIN, starts the trip with the rider's PIN and
  completes it. The picks are random so that a rerun after a failed run, which can leave a ride open, starts afresh.
- The concurrency races repeat 200 times each (LLD §17.2); `./gradlew test -PraceRepetitions=20` runs them quicker.
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
