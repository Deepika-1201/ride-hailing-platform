# Requirements — Ride-Hailing Platform

| | |
|---|---|
| Phase | 2 — Requirements (baseline) |
| Status | Approved: Phase 1 defaults accepted on 2026-10-02 |
| Next | [ADR-001](decisions/ADR-001-architecture-style.md) (accepted) → design spikes (§12, done) → [HLD](architecture.md) (approved) → [LLD](low-level-design.md) → [implementation plan](implementation-plan.md) |

Items marked **(assumed)** were not explicitly discussed and stay open for challenge.

---

## 1. Product summary

A ride-hailing and dispatch platform built as a real-time distributed system. Riders get an upfront fare, book a ride and watch their driver approach on a live map. Drivers stream their location, receive one ride offer at a time and move each ride through an explicit state machine from pickup to drop-off. The platform matches each booking to a nearby driver within seconds and guarantees that a driver is never assigned to two rides at once.

This is the real-time project of a four-project portfolio (job scheduler, e-commerce order system, payment gateway, ride-hailing). Its depth goes into location ingestion, geospatial search, dispatch and concurrency. Payments, sagas and general-purpose scheduling stay deliberately thin because the sibling projects cover them. It follows the same conventions but has no runtime dependency on them.

Riders, drivers and payments are simulated; the engineering targets production standards. Priorities, in order: correctness, reliability, understandability, scalability, extensibility, performance.

## 2. Scope decisions (from Phase 1)

| # | Topic | Decision |
|---|---|---|
| Q1 | Market | India, INR, IST. Bengaluru first, on real OpenStreetMap data. A second city in V6 to exercise city partitioning |
| Q2 | Scale | Three tiers: laptop (built and tested), cloud load test, designed for (§7) |
| Q3 | Apps | No mobile apps. A headless simulator for drivers and riders, plus one small web app with a live map for operations and demos |
| Q4 | Fares | Upfront: a quote fixes the price before booking. Re-pricing on route or stop changes is designed for, not built |
| Q5 | Offers | Sequential: one live offer per driver, 15 s to answer, up to 3 min of searching. Matching strategies are pluggable; batch matching is evaluated in V4 |
| Q6 | Shared rides | Out of scope for the whole roadmap. Back-to-back trips are designed in V4 |
| Q7 | Payments | Pay after the trip, mock payment provider only. Unpaid amounts become rider dues; cash is recorded without settlement logic. No ledger or reconciliation |
| Q8 | Stack | Java 25 + Spring Boot 4.1, plain JDBC, Flyway, OpenTelemetry: the Payment Orchestrator's conventions |
| Q9 | Workflow | Hand-built state machine, outbox and durable timers, reusing the Job Scheduler's lease patterns. No workflow engine or saga framework |
| Q10 | Cloud | AWS ap-south-1 (Mumbai), ECS Fargate, Terraform; temporary environments with a budget alarm. Budget cap and EKS decided before V7 |
| Q11 | Local runtime | Colima (OrbStack needs macOS 14+) with Docker Compose profiles |
| Q12 | Scope | Ride categories configured per city (auto, mini, sedan, XL). V1 includes rule-based surge, saved places, two-way ratings, cancellation fees and a ride PIN |
| Q13 | Cancelling and waiting | Policies in §5 |
| Q14 | Driver onboarding | Seed data or admin API; verification is an admin-set status. Document upload and KYC out of scope |
| Q15 | Offline driver app | Trip start and end work offline and sync later, applied once |
| Q16 | Targets | §6 |
| Q17 | Location privacy | No location history for idle drivers; trip routes kept 90 days; operations access audited |
| Q18 | Repository | `Deepika-1201/ride-hailing-platform`, pushed as work progresses |
| — | Architecture | Modular monolith with an event-driven core and runtime roles ([ADR-001](decisions/ADR-001-architecture-style.md)) |
| — | Documents | Sibling layout (requirements, HLD, LLD, implementation plan, OpenAPI, runbooks, ADRs) plus three deep dives: location system, dispatch, ride lifecycle. Full detail for V1–V4; V5–V8 get decisions now and detail when they start |

## 3. Actors

| Actor | Interaction |
|---|---|
| Rider | Gets quotes, books and cancels rides, tracks the driver, pays, rates. Simulated, or a person using the demo web app |
| Driver | Goes online and offline, streams location, answers offers, runs the trip, rates. Simulated, or a person using the demo web app |
| Operations staff | Watch rides and the live map, investigate failures, suspend drivers, cancel rides, refund or waive fees, replay events |
| Admin | Manages cities, zones, ride categories, fare and surge rules, fees, drivers and vehicles |
| Payment provider (mock) | Charges and refunds, status checks, webhooks; produces realistic latency, failures, timeouts and duplicate webhooks |
| Routing provider | Route distance and duration: a mock first, OSRM on OpenStreetMap later |
| Simulator | Moves drivers along real roads, generates demand by zone and hour, injects faults |

## 4. Functional requirements

The tag at the end of each requirement is the version that first delivers it (§8).

### 4.1 Identity and access
- **FR-I1** Riders and drivers sign in with a phone number and a one-time code; local and test environments use a fixed code. (V1)
- **FR-I2** Roles are `RIDER`, `DRIVER`, `OPS` and `ADMIN`. Every request is checked for role and ownership: riders see only their own rides, and drivers see only rides offered or assigned to them. (V1)

### 4.2 Riders
- **FR-R1** Profile with name, phone and payment methods (mock card or UPI references, or cash); one method is the default. (V1)
- **FR-R2** Up to 10 saved places, such as home and work. (V1) **(assumed)**
- **FR-R3** Ride history with fares and a receipt for each completed ride. (V1)
- **FR-R4** Unpaid dues block new bookings until they are paid. (V1)

### 4.3 Drivers and vehicles
- **FR-D1** Drivers and vehicles come from seed data or the admin API. A vehicle belongs to one driver and has a category, plate number, make, model and colour. (V1)
- **FR-D2** Verification status (`PENDING`, `VERIFIED`, `REJECTED`) is set by admins. Only verified, unsuspended drivers with an active vehicle can go online. (V1)
- **FR-D3** Drivers go online with a chosen vehicle and go offline at will, except during an active ride. (V1)
- **FR-D4** Trip history and earnings per trip (fare, commission, net) and per day and week, computed from completed rides. Cash rides show the fare as collected by the driver. (V1)
- **FR-D5** Operations suspend and reinstate drivers. Suspension blocks new offers at once; an active ride continues unless operations cancel it. (V1)

### 4.4 Location
- **FR-L1** Online drivers send a location update every 4 s with position, accuracy, heading, speed, device time and a per-driver sequence number. (V1 over HTTP, V2 over WebSocket)
- **FR-L2** An update older than the latest accepted one for that driver, by sequence number, is dropped; duplicates are ignored. (V1)
- **FR-L3** A driver not heard from for 30 s is left out of matching. After 10 min without contact and with no active ride, the driver is taken offline. (V1) **(assumed: 10 min)**
- **FR-L4** Updates with accuracy worse than 100 m, or implying a speed above 150 km/h since the previous one, are flagged and not used for matching. (V2) **(assumed thresholds)**
- **FR-L5** From assignment until drop-off, the rider sees the driver's live position and an updated pickup ETA. Nobody else except operations sees an individual driver's live position. (V2)
- **FR-L6** Updates from assignment to drop-off form the trip route. Updates sent late, after the app was offline, are added when they arrive. (V2)

### 4.5 Pricing
- **FR-PR1** A quote takes pickup, drop-off and category and returns the upfront fare, a pickup ETA estimate and an expiry time (5 min). Quotes are immutable and identified by `quote_id`. (V1) **(assumed: 5 min)**
- **FR-PR2** Fare = max(minimum fare, (base + per-km rate × route km + per-minute rate × route minutes) × surge multiplier) + booking fee, then tax; rounded up to whole rupees and stored in paise. Fare rules are versioned per city and category, and each quote records the rule version it used. (V1)
- **FR-PR3** Surge is a multiplier per zone, capped at 2.0×. In V1 it comes from admin-set rules by zone and time window. From V4 it is computed from live demand and supply per zone, recalculated every minute and smoothed so it doesn't flicker. (V1, V4) **(assumed: cap and cadence)**
- **FR-PR4** Cancellation and no-show fees are configured per city and category. (V1)

### 4.6 Rides
- **FR-RD1** A rider books a ride from a valid, unexpired quote of their own, with an idempotency key. A rider has at most one active ride. (V1)
- **FR-RD2** Every ride follows an explicit state machine: `SEARCHING` → `DRIVER_ASSIGNED` → `DRIVER_ARRIVED` → `IN_TRIP` → `COMPLETED`. The other terminal states are `CANCELLED_BY_RIDER`, `CANCELLED_BY_DRIVER`, `CANCELLED_BY_SYSTEM` and `DRIVER_NOT_FOUND`. Illegal transitions are rejected, and every transition is recorded with actor, reason and time. Payment status is tracked separately (§4.8). (V1)
- **FR-RD3** The rider can cancel in any state before `IN_TRIP`; fees follow §5. (V1)
- **FR-RD4** If the driver cancels before arriving, the ride returns to `SEARCHING` with priority and that driver is excluded. After the waiting time at pickup, the driver can end the ride as a no-show (`CANCELLED_BY_DRIVER`, reason `NO_SHOW`). (V1)
- **FR-RD5** The trip starts only when the driver enters the rider's 4-digit ride PIN. (V1)
- **FR-RD6** The driver ends the trip at drop-off; the fare is the quoted fare. (V1)
- **FR-RD7** All ride commands (book, cancel, accept, decline, arrive, start, end) are idempotent: a retry with the same key returns the original result. (V1)
- **FR-RD8** Trip start and end made while the driver app is offline carry a client-generated ID and the device time. The server applies each one late but only once, and keeps both device and server times. (V2)

### 4.7 Dispatch
- **FR-DS1** After booking, dispatch looks for available, recently heard-from drivers of the requested category near the pickup, widening the search radius over time (2 km up to 6 km). (V1) **(assumed radii)**
- **FR-DS2** Candidates are ranked by a pluggable strategy. V1 ranks by straight-line distance; V4 ranks by road ETA from the routing provider and adds further strategies. (V1, V4)
- **FR-DS3** Offers go to one driver at a time, and a driver holds at most one live offer. An offer expires after 15 s; a declined or expired offer moves on to the next candidate. A driver is never offered the same ride twice. (V1)
- **FR-DS4** Accepting a live offer assigns the driver atomically. A late or repeated acceptance gets a clear "no longer available" answer and changes nothing. (V1)
- **FR-DS5** If no driver accepts within 3 min, the ride ends as `DRIVER_NOT_FOUND` and the rider is told. (V1)
- **FR-DS6** Every dispatch decision (candidates considered, ranking, offers and their outcomes) is recorded, so operations can answer "why didn't this ride get a driver?". (V1)

### 4.8 Payments
- **FR-PY1** When a trip completes, the rider's default method is charged the fare through the payment provider, with an idempotency key derived from the ride. Cash rides are recorded as paid in cash. (V1)
- **FR-PY2** Each charge has its own state machine: `PENDING`, `SUCCEEDED`, `FAILED` and `UNKNOWN`. A provider timeout means `UNKNOWN`, resolved by status checks and webhooks, never by a blind retry. (V1)
- **FR-PY3** A failed charge becomes rider dues; the rider can retry the payment. (V1)
- **FR-PY4** Cancellation and no-show fees are charged the same way. (V1)
- **FR-PY5** Operations can refund a charge fully or partly, for example to waive a fee. (V1)
- **FR-PY6** Provider webhooks are verified and deduplicated, and may arrive before, after or instead of the API response. (V1)

### 4.9 Notifications
- **FR-N1** Riders and drivers are notified in the app when a driver is assigned, arriving (V2, from location), arrived, the trip started and completed, a payment succeeded or failed, the ride was cancelled, or no driver was found. (V1)
- **FR-N2** Push and SMS go through a `NotificationProvider` interface; V1 ships log-only mock providers. Delivery is retried with backoff, and a failed notification never affects the ride. (V1; broker-based retries from V3)

### 4.10 Ratings
- **FR-RT1** After a completed ride, rider and driver can rate each other once (1–5 stars, optional comment) within 7 days. (V1) **(assumed: 7 days)**
- **FR-RT2** The rating shown for a person is the average of their latest 100 ratings. (V1) **(assumed)**

### 4.11 Operations and admin
- **FR-O1** Find a ride by ID and see its full timeline: state changes, dispatch decisions, offers, payments and notifications, linked by correlation IDs. (V1)
- **FR-O2** List active rides, drivers by status, failed payments and rides that ended `DRIVER_NOT_FOUND`. (V1)
- **FR-O3** Operations can cancel a ride (`CANCELLED_BY_SYSTEM`) with a reason. (V1)
- **FR-O4** Admin APIs manage cities, zones, ride categories, fare and surge rules (versioned) and fees. (V1)
- **FR-O5** Live map of drivers and active rides in a city. (V2)
- **FR-O6** Re-drive dead-lettered events and replay events to a consumer. (V3)

### 4.12 Audit
- **FR-A1** An append-only audit log records ride state changes, driver status and verification changes, payment operations, rule changes and every operations or admin action: who, what, when, why, request ID, entity, previous state and new state. (V1)
- **FR-A2** Operations reading a trip route is itself audited. (V2)

### 4.13 Simulator and demo
- **FR-S1** A scripted demo runs one ride end to end against the local stack. (V1)
- **FR-S2** The simulator moves thousands of drivers along real Bengaluru roads, with shifts and realistic offer acceptance and response times. (V2)
- **FR-S3** The simulator generates rider demand by zone and hour (office peaks, airport, stadium events, New Year's Eve), including cancellations. (V2)
- **FR-S4** The simulator injects faults: disconnects, app restarts, and delayed, duplicated and out-of-order location updates. (V2)
- **FR-S5** Simulator runs are reproducible from a seed and report rider wait times, pickup distances, match rate and cancellation rate. (V2)
- **FR-S6** A small web app shows the live map and lets a person act as one rider and one driver. (V2)

## 5. Policies

Defaults below are configurable per city and category.

| Policy | Default |
|---|---|
| Quote validity | 5 min **(assumed)** |
| Offer timeout | 15 s |
| Search timeout | 3 min, then `DRIVER_NOT_FOUND` |
| Search radius | Starts at 2 km and widens to 6 km **(assumed)** |
| Driver freshness | Left out of matching after 30 s without an update; taken offline after 10 min without contact and with no active ride |
| Rider free cancellation | Until 2 min after assignment, or at any time while the driver is more than 5 min behind the ETA promised at assignment |
| Rider cancellation fee | Charged after the free window; amounts in seed data are illustrative |
| Waiting at pickup | 5 min after `DRIVER_ARRIVED`; then the driver may end the ride as a no-show and the rider pays the no-show fee |
| Driver cancellation before arrival | Ride returns to `SEARCHING` with priority, excluding that driver; counted in the driver's cancellation rate |
| Rider dues | Any unpaid amount blocks new bookings |
| Ride PIN | 4 digits, generated at assignment, shown only to the rider |
| Ratings | Once per side, within 7 days **(assumed)** |
| Trip routes | Kept 90 days, then deleted |
| Surge cap | 2.0× **(assumed)** |

## 6. Non-functional requirements

- **NFR-1 Correctness (hard invariants).** A driver never holds two active rides or two live offers. A ride never has two drivers. Rides change state only along allowed transitions. No charge is created twice for the same ride and purpose. An acknowledged booking is never lost. Concurrency and failure tests prove each invariant.
- **NFR-2 Availability.** Booking path 99.9% per month (design target, verified at the cloud tier).
- **NFR-3 API latency.** p99 ≤ 300 ms for booking and driver commands; p99 ≤ 500 ms for quotes, which include a routing call. Measured at the server.
- **NFR-4 Dispatch latency.** First offer sent within 2 s of booking (p95) when a candidate exists.
- **NFR-5 Location freshness.** A location update reaches the matching index and the rider's screen within 1 s (p95).
- **NFR-6 Durability.** No acknowledged write to rides, offers, payments or audit is ever lost (RPO 0 within the region). Live positions may be lost; they rebuild from new updates within one update interval. Trip routes tolerate small gaps.
- **NFR-7 Recovery.** After any single process crash, in-flight rides, offers and timers resume within 30 s. After losing Redis, matching resumes within 10 s. While the broker is down, booking keeps working and side effects catch up afterwards.
- **NFR-8 Scalability.** Every runtime role scales horizontally, data and work partition by city, and the tiers in §7 are reachable without redesign.
- **NFR-9 Security.** Every API is authenticated and checks role and ownership. TLS in the cloud; secrets never in the repository; rate limits per user and per IP; input validated at every boundary.
- **NFR-10 Privacy.** Location data is minimized as in Q17. Riders and drivers see each other's first name and rating, and riders see vehicle details; neither sees the other's phone number.
- **NFR-11 Observability.** Structured JSON logs carry trace, request and correlation IDs. RED metrics per API, plus domain metrics: dispatch funnel, location update age, offer outcomes, payment failures. Traces span HTTP, broker and WebSocket hops. Dashboards and SLO burn-rate alerts, as in the sibling projects.
- **NFR-12 Operability.** Rolling deploys without downtime, including draining WebSocket connections. Every alert has a runbook.
- **NFR-13 Local development.** `docker compose up` starts the default stack inside the 4 GB container VM; observability and routing start through Compose profiles; seed data loads automatically.
- **NFR-14 Testability.** Unit, integration (Testcontainers), contract, end-to-end, concurrency, failure and load tests. Simulator runs are reproducible from a seed.
- **NFR-15 Maintainability.** Module boundaries are enforced by tests, and every significant decision has an ADR.
- **NFR-16 Cost.** Cloud environments exist only while needed, under a budget alarm.
- **NFR-17 Data conventions.** Money in paise with a currency code; timestamps stored in UTC and shown in IST; server-generated UUIDv7 identifiers, except client-generated idempotency keys and offline command IDs. **(assumed: UUIDv7)**

## 7. Capacity model

Assumptions **(assumed)**: one location update every 4 s per online driver; at peak, 60% of online drivers are on a ride; a ride lasts about 25 min including pickup; average load is 40% of peak; bookings are 1.25 × completed rides (cancellations and no-driver outcomes); quotes are 5–10 × bookings; open WebSockets are online drivers plus riders with an active ride.

| | Laptop (built and tested) | Cloud load test (temporary) | Designed for (on paper) |
|---|---|---|---|
| Cities | 1 | 1–2 | 10+ |
| Online drivers at peak | 2,000 | 50,000 | 500,000 |
| Location updates/s | 500 | 12,500 | 125,000 |
| Active rides at peak | ~1,200 | ~30,000 | ~300,000 |
| Bookings/s at peak | ~1 | ~30 | ~300 |
| Quotes/s at peak | ~10 | ~300 | ~3,000 |
| Open WebSockets | ~3,000 | ~80,000 | ~800,000 |
| Tracking messages to riders/s | ~300 | ~7,500 | ~75,000 |
| Rides per day | ~28k | ~0.7M | ~7M |

What the numbers imply:
- **Location ingestion** is the hottest path: about 400 location updates for every booking. At ~120 bytes per update, bandwidth is small (~1.5 MB/s at the cloud tier); the message rate and the work per message are what matter.
- **Transaction writes** run to about 30 rows per ride (state changes, offers, timers, outbox, audit, payment): ~900 writes/s at the cloud tier and ~9,000/s designed-for. One PostgreSQL primary carries the cloud tier; partitioning by city is the path beyond it.
- **Live index memory** is about 300 bytes per online driver, so ~150 MB for 500,000 drivers. The update rate per node, not memory, is the limit.
- **Trip routes** hold ~375 points per ride: ~45 KB as PostgreSQL rows, a few KB compressed. That is ~30 GB/day at the cloud tier and ~300 GB/day designed-for, so routes cannot live in PostgreSQL for long at scale; the HLD decides where they go after a ride.
- **Hotspot test (cloud tier):** 10× normal demand in one zone for 15 min (stadium exit, airport wave, New Year's Eve). Expected: no double assignment, no retry storms, quote latency within target, other zones unaffected.

## 8. Delivery plan by version

| Version | Delivers | Done when |
|---|---|---|
| V1 Walking skeleton | All requirements tagged V1: identity, profiles, drivers and vehicles, quotes and fare rules, the ride state machine, simple matching with single offers and timers, mock payments, fees and dues, ratings, in-app notifications, audit, admin and operations APIs. Location over HTTP. Events written to an outbox and delivered in-process. REST API with OpenAPI. Scripted demo | A ride runs end to end, and concurrency tests prove the NFR-1 invariants |
| V2 Real-time location | WebSockets for drivers (updates, offers) and riders (tracking, status), Redis live index, location quality rules, trip routes, offline sync, simulator, live map web app | Laptop tier sustained: 2,000 drivers, NFR-5 met |
| V3 Event-driven | Broker, outbox relay, consumers (notifications, tracking fan-out, demand and supply counters), idempotent consumers, retries, dead-letter queue, replay | Tests with duplicated, delayed and reordered messages and a broker outage pass |
| V4 Dispatch | Widening candidate search, ETA ranking through the routing provider, dispatch role owning cities, live surge, a batch-matching experiment, back-to-back trip design | NFR-4 met in simulator runs; contention tests show zero double assignments |
| V5 Service extraction | Extraction only with a measured reason; first candidate: the realtime gateway with location ingestion | The same test suites pass across process boundaries |
| V6 Scale | Second city, city partitioning, cloud-tier load and hotspot tests | Cloud-tier targets met |
| V7 Production deployment | Terraform on AWS, CI/CD, secrets, autoscaling, dashboards, SLO alerts, runbooks | A deploy under live WebSocket traffic causes no downtime |
| V8 Failure engineering | Fault injection: killed roles, database, Redis and broker faults, network faults, duplicated, delayed and reordered messages | NFR-7 recovery targets met |

## 9. Out of scope

- **Designed for, not built** (extension points only): scheduled rides, multiple stops, changing the destination mid-trip, airport queues, corporate accounts, promotions and coupons, driver incentives, back-to-back trips (designed in V4), ML-based matching, ETA and pricing.
- **Not planned:** shared rides, guest bookings, mobile apps, real money, driver payouts, document upload and KYC, turn-by-turn navigation, masked calling, active-active multiple regions.

## 10. Assumptions (defaults applied until challenged)

- **A-1** All users and payments are simulated; no real money moves.
- **A-2** Map data comes from OpenStreetMap (ODbL), with attribution wherever a map appears.
- **A-3** GPS error is 5–50 m, device clocks can be minutes off, and connectivity drops for seconds to minutes. **(assumed)**
- **A-4** Fare rules, fees, taxes and surge caps in seed data are illustrative, not legal tariffs. **(assumed)**
- **A-5** One developer and one AWS region.
- **A-6** The capacity assumptions in §7 hold. **(assumed)**

## 11. Constraints

- **C-1** Development machine: Apple Silicon, 8 GB RAM, macOS 13.4. Containers run in a 4 GB Colima VM, and the default local stack must fit in it.
- **C-2** No runtime dependency on the sibling projects; payments, sagas and general scheduling stay thin.
- **C-3** The Payment Orchestrator's stack conventions: Java 25, Spring Boot 4.1, JDBC, Flyway, OpenTelemetry.
- **C-4** Free and open data and tools only: no paid map or routing APIs.
- **C-5** No product code before the design is approved. Spikes are throwaway measurements.

## 12. Open questions for design spikes

| Spike | Question | Answer |
|---|---|---|
| S-1 | Live index: Redis GEO vs Redis with H3 cells vs PostGIS, at laptop and cloud-tier rates (query p99, update throughput, CPU) | Valkey GEO sets: 0.26 cores vs 1.46 for PostGIS at the cloud tier ([results](../spikes/results/s1-live-index.md), [ADR-004](decisions/ADR-004-live-location-index.md)) |
| S-2 | Timers: does a PostgreSQL timer table fire offer timeouts within ~1 s at cloud-tier rates without loading the database? | Yes: at most 0.3 s late at 10× the cloud rate, 0.26 cores ([results](../spikes/results/s2-timers.md), [ADR-005](decisions/ADR-005-durable-timers.md)) |
| S-3 | WebSockets: memory per connection, and how a message reaches the node that holds a driver's connection | Tomcat with 1 KB buffers, ~21 KB per connection; Valkey pub/sub subject channels ([results](../spikes/results/s3-websockets.md), [ADR-006](decisions/ADR-006-realtime-transport.md)) |
| S-4 | Outbox relay throughput and per-ride ordering | Open; measured when the broker arrives in V3 |

Later, once the simulator exists: whether batch matching beats sequential offers, the cost of ETA calls, and the partition key for the location stream.

## 13. Glossary

| Term | Meaning |
|---|---|
| Location update | A driver's position report, sent every 4 s while online |
| Live index | The fast, approximate store of current driver positions used for matching |
| Trip route | The location updates recorded from assignment to drop-off |
| Quote | An immutable, expiring upfront price for a pickup, drop-off and ride category |
| Offer | A ride proposed to one driver, live for 15 s |
| Reservation | The atomic claim on a driver while an offer to them is live |
| Assignment | A driver accepting an offer; the ride moves to `DRIVER_ASSIGNED` |
| Zone | A map area used for surge pricing and demand and supply counts |
| Surge multiplier | A per-zone factor applied to the fare when demand outstrips supply |
| Rider dues | Unpaid amounts that block new bookings |
| Ride PIN | A 4-digit code the rider gives the driver to start the trip |
| Runtime role | One of `api`, `realtime`, `dispatch` or `worker`: the same application started with different parts enabled ([ADR-001](decisions/ADR-001-architecture-style.md)) |
| Outbox | Events stored in the same database transaction as the change they describe, and published afterwards |
| Idempotency key | A client-chosen key that makes a retried command return the original result |
