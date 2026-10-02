# Implementation plan

| | |
|---|---|
| Phase | 5 — Implementation plan |
| Status | Approved 2026-10-02, with the [LLD](low-level-design.md) and the [architecture review](architecture-review.md). Current phase: 1 (scaffolding) |
| Builds | [Requirements](requirements.md) §8 (V1–V8), designed in the [HLD](architecture.md) and [LLD](low-level-design.md) |

## Working agreement

- Work proceeds one vertical slice at a time, in the phases below. Code starts only after the design is approved (requirements C-5).
- Before a phase starts, its LLD sections are re-read against what the earlier phases taught, and any change is written into the LLD, with an ADR if the decision is significant. Then the code follows.
- Every phase ends with its work committed and pushed to `Deepika-1201/ride-hailing-platform`.

## Definition of done (every phase)

- The phase's exit criteria are met and shown by tests, not by inspection.
- Unit, architecture, integration and contract tests are green locally and in CI.
- `openapi.yaml` and the JSON Schemas match every endpoint, event and message the phase added or changed.
- The LLD, HLD and ADRs reflect what changed; numbers introduced on the way are marked **(assumed)**.
- New metrics are registered and visible in `/actuator/prometheus` (management port).

## Phases

### V1 — walking skeleton (PostgreSQL only)

| # | Phase | Scope (LLD sections) | Tests added | Exit criteria |
|---|---|---|---|---|
| 0 | Design | Requirements, spikes S-1 to S-3, HLD, three deep dives, LLD, OpenAPI, schemas, ADR-001 to ADR-022, architecture review | — | Final design approval: **done 2026-10-02** |
| 1 | Scaffolding | Gradle (Kotlin DSL), Java 25, Spring Boot 4.1; the 14 module packages with allowed dependencies; roles and `@ConditionalOnRole`; configuration; problem details and request IDs; health; JSON logs; per-module Flyway with empty schemas; PostgreSQL + PostGIS image; `docker compose`; GitHub Actions (§1, §2.1, §4.9, §13.1) | Spring Modulith verification; a fixture proving a forbidden dependency fails the build; schema-ownership test; roles start alone and together; problem-details format | `./gradlew build` green in CI; the app starts in each role; a boundary violation fails the build |
| 2 | Platform | IDs and clocks; idempotency keys; outbox, in-process relay, inbox and failed deliveries; timers and their poller; leases; audit log with partitions and the append-only trigger; maintenance and retention jobs (§5) | Same-key replay, different-request `422`, in-progress `409`; crash between commit and delivery (no loss, no duplicate effect); poison event set aside; timer fires after a rollback; lease takeover with fencing; audit rejects updates | Every platform mechanism proven by a failure test, not only a happy path |
| 3 | Identity and access | One-time codes, tokens, refresh rotation with reuse detection, roles, ownership plumbing, in-memory rate limiter, seeded staff accounts (§12) | Code attempts and expiry; refresh rotation, grace window, family revocation; wrong-role and wrong-owner access for a sample endpoint per role; rate limits | Every later endpoint can declare its role and get ownership checks for free |
| 4 | Reference data and profiles | Cities, service areas, special areas, categories and dispatch settings; fare, fee and surge rules; drivers, vehicles, verification; rider profiles, places and payment methods; Bengaluru seed data (§4.3, §4.4, §10.5) | Admin API contract tests; geometry validation; rule versioning (never edited, never in the past); zone resolution (special area over H3 cell) | Seeded local stack answers every admin and profile endpoint in the OpenAPI document |
| 5 | Quotes | Mock routing provider, fare calculation, surge-rule lookup, quotes with expiry, pickup ETA estimate (§10) | Property tests: components add up to the total, rounding 0–99 paise, never below the minimum; surge windows across midnight; expired and foreign quotes | Quotes match the worked example in LLD §10.2 |
| 6 | Drivers online and location | Availability rows and sessions; go online and offline; location over REST into the in-memory live index; sequence ordering; mirror writes; sweeper with its safety valve; reconciler (§8.1, §8.2, §8.9, §8.10, §9.2, §9.6) | The live-index contract suite (in-memory implementation); stale and duplicate updates; eligibility rules; sweeper rules and both safety-valve triggers | A seeded driver goes online, streams locations, and is found by `nearby` |
| 7 | Booking and dispatch | Booking (T1), search tasks and attempts, nearest ranker, decision log, offers with timers, polling of the current offer, accept (T2), decline, expiry, search timeout (T3), rider cancel while searching (T4) (§7.2–§7.4, §8.3–§8.7) | Concurrency: races 1–3, accept against expiry and against search timeout, cancel during an attempt, double booking; invariant checks I1–I3; first-offer latency measured with 200 drivers | Booking to first offer under 2 s p95 locally; zero invariant violations over 200 repetitions of every race |
| 8 | Ride lifecycle | Arrive, PIN start, no-show, complete, driver and rider cancellations with fees, unreachable driver (T7), operations cancel, flags, stuck-ride detector (§7) | Every transition and every rejection code; races 4, 5, 8, 10; fee windows at their boundaries; PIN lock; natural idempotency of every command | All 13 transitions and their `409`s covered; I4–I6 hold after the race suite |
| 9 | Payments | Charges from events, the executor, status checks, webhooks, late-success refunds, dues and paying them, refunds, earnings, the mock provider (§11) | Each mock token's path; timeout then success; webhook before, after and instead of the response; duplicate webhooks; crash during a provider call; race 6; refund limits | No double charge in any test; unknown outcomes always resolve or reach operations; I7 holds |
| 10 | Notifications and ratings | Notification consumers and deliveries with backoff; rating windows, ratings, summaries (§15.4, §4.7) | Duplicate events (race 7); dead deliveries never affect rides; rating window and once-per-side rules | Every FR-N1 notification kind produced by the end-to-end test |
| 11 | Operations | Ride lists, timeline, driver lists, suspension and reinstatement, payments list, refunds, flags (§8.8, §15.2) | Suspension withdraws an offer in the same transaction; timeline of a full ride, including a reassignment and a refund | A ride's timeline answers "why did this take so long?" from data alone (FR-O1, FR-DS6) |
| 12 | V1 complete | Full race suite and invariant checks; contract coverage of every endpoint and event; `scripts/demo.sh`; README run instructions | The suite runs in CI on every push | **V1 done (requirements §8):** a ride runs end to end through the demo script, and concurrency tests prove the NFR-1 invariants |

### V2 — real-time location

| # | Phase | Scope | Exit criteria |
|---|---|---|---|
| 13 | Valkey | Lettuce client, script loading, the Valkey live index with all five scripts, Valkey rate limiter and tickets, epoch, reconciler against Valkey (§9.3–§9.4, ADR-020) | The live-index contract suite passes on both implementations; a 3-shard cluster test passes; total loss of Valkey recovers matching within 10 s (NFR-7) |
| 14 | Realtime | WebSocket endpoint, tickets, channels including `rdr:`, pushes, coalescing, heartbeats, draining; quality rules; tracking and ETA; trip points; offline replays over REST; offline ride commands (§9.5–§9.8, §14, §7.10) | WebSocket message contract tests; a node drain moves every client without losing a ride; replays land in the trip route exactly once |
| 15 | Simulator and web app | Go simulator with scenarios, faults and reports; OSRM routing data and recorded routes; `GET /v1/ops/invariants`; React web app with the operations map, rider and driver screens (§17.3, §18, ADR-021, ADR-022) | A recorded scenario runs in CI without OSRM; the operations map shows a live run |
| 16 | Laptop tier | 2,000 drivers with rider demand for 30 min on the laptop; tuning | **V2 done:** NFR-5 met (location to rider's screen within 1 s p95); invariant checks clean after the run |

### V3 to V8

| # | Version | Scope | Done when (requirements §8) |
|---|---|---|---|
| 17 | V3 Event-driven | Spike S-4; Kafka topics, relay to Kafka, consumer groups, dead letters, replay, location stream (§19.1) | Tests with duplicated, delayed and reordered messages and a broker outage pass |
| 18 | V4 Dispatch | OSRM provider and route cache; ETA and weighted rankers; city ownership; computed surge; batch-matching experiment (§19.2) | NFR-4 met in simulator runs; contention tests show zero double assignments; batch matching adopted or rejected with numbers |
| 19 | V5 Extraction | Only with a measured reason; LLD section written first | The same test suites pass across process boundaries |
| 20 | V6 Scale | Second city, partitioning, read replica, cloud-tier load and hotspot tests | Cloud-tier targets met |
| 21 | V7 Production deployment | Terraform, CI/CD, secrets, autoscaling, dashboards, alert tests, runbooks | A deploy under live WebSocket traffic causes no downtime |
| 22 | V8 Failure engineering | Fault injection across roles, stores and networks | NFR-7 recovery targets met |

## Risks the order addresses

| Risk | Where it is retired |
|---|---|
| Locking and deadlocks in the assignment path (review finding R-01) | Phase 2 proves the timer and claim mechanics; phase 7 runs the races before anything else depends on dispatch |
| Idempotency and outbox mistakes spreading to every module | Phase 2 builds and failure-tests them once, before any business module |
| Module boundaries eroding | Phase 1 makes a forbidden dependency fail the build |
| The live index's behaviour changing between V1 and V2 | One contract suite, written in phase 6 and run against both implementations from phase 13 |
| Payment edge cases left to the end | Phase 9 has its own failure tests with the mock's deterministic tokens |
| Real-time targets missed late | Phase 16 measures NFR-5 at the laptop tier before Kafka is added |

## Local prerequisites

- JDK 25 (installed under `~/.jdks`), Gradle through the wrapper.
- Docker through Colima for Testcontainers and Compose; phase 1 records the Testcontainers settings Colima needs.
- From V2: Go (as for the spikes) and Node.js 20 or later for the web app.

## Documents still to come

| Document | Written in |
|---|---|
| `runbooks.md` | With each alert, complete in V7 |
| Capacity report | Phase 16 (laptop tier) and V6 (cloud tier) |
| Deployment and cost notes | V7 |
