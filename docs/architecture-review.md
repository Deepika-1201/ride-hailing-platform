# Architecture review

| | |
|---|---|
| Phase | 6 — Architecture review (brief §59), before implementation |
| Date | 2026-10-02 |
| Reviewed | [Requirements](requirements.md), [HLD](architecture.md), [ride lifecycle](ride-lifecycle.md), [dispatch](dispatch-design.md), [location system](location-system.md), [LLD](low-level-design.md), [OpenAPI](openapi.yaml), [schemas](schemas/), [ADR-001 to ADR-022](decisions/README.md) |
| Outcome | Ready for implementation. Every high- and medium-severity finding that could be fixed on paper was fixed in the documents; the rest are tracked to the version that settles them |

## 1. Method

The review walked the design seven ways:

1. Every NFR-1 invariant and every race, step by step against the SQL and lock order in the LLD.
2. Every component failure in HLD §12, following the recovery path to the end.
3. Single points of failure.
4. The first bottleneck at each capacity tier (requirements §7).
5. Threats, by STRIDE category.
6. Complexity: what could be simpler, and why it isn't.
7. Consistency between the documents, the OpenAPI document and the schemas.

| Severity | Meaning |
|---|---|
| High | Could break an invariant, lose acknowledged data, or take a city out of service |
| Medium | Degrades availability, latency, privacy or operability |
| Low | Cost, clarity or a narrow edge case |

Resolutions: **Fixed** (the design changed), **Accepted** (a known risk, taken deliberately), **Tracked** (settled in a named version or phase).

## 2. Findings

| ID | Area | Finding | Severity | Resolution |
|---|---|---|---|---|
| R-01 | Concurrency | The lock order put timers last, but the timer poller locks a timer *first* and then the ride. An acceptance holding the ride and deleting the search timer, and the search-timeout handler holding that timer and waiting for the ride, could deadlock. Search tasks had the same shape | High | **Fixed.** Pollers claim tasks and timers first; every other transaction removes them without waiting (`SKIP LOCKED`), and handlers re-check state ([LLD §6.2](low-level-design.md#62-tasks-and-timers-are-never-waited-on)). [ADR-010](decisions/ADR-010-state-machines.md) amended |
| R-02 | Concurrency | A rider's cancellation could commit between a search attempt's "still searching?" check and its new offer, leaving a driver held for 15 s on a cancelled ride | Medium | **Fixed.** The attempt takes a share lock on the ride ([LLD §6.4](low-level-design.md#64-why-the-search-attempt-takes-a-share-lock-on-the-ride)) |
| R-03 | Concurrency | Suspension starts from the driver and must withdraw an offer; locking availability before the offer would break the order | Low | **Fixed.** The driver profile has its place in the order (ride → driver → offer → availability), and paths that start from a driver read first, then lock in order and re-check ([LLD §6.1](low-level-design.md#61-lock-order)) |
| R-04 | Live index | Status-mirror writes run after commit and can arrive out of order. Deleting the availability row when a driver went offline also restarted its version. A stale write could hide an available driver, or show an unavailable one, until the next reconciliation | Medium | **Fixed.** Availability rows are kept with status `OFFLINE`, so versions only grow; the mirror script ignores older versions; offline drivers leave a tombstone ([LLD §9.4](low-level-design.md#94-scripts)) |
| R-05 | Availability | After a total loss of Valkey, a `realtime` outage or a network partition, every driver looks silent. The sweeper would unassign every assigned ride after 2 min and take every driver offline after 10 min | High | **Fixed.** An index epoch, plus a safety valve: no automatic action while the index is younger than the rule's threshold, or when a rule would hit more than 10% of a city's drivers at once; an alert instead ([LLD §8.9](low-level-design.md#89-sweeper)) |
| R-06 | Dispatch | "Three expired offers in a row → offline" punished drivers whose offers were never delivered. A push outage could take a whole city's drivers offline | Medium | **Fixed.** Only offers the app acknowledged (`seen_at`) count ([LLD §8.6](low-level-design.md#86-offer-expiry)) |
| R-07 | Realtime | ADR-006 sized WebSocket buffers at 1 KB (spike S-3), but location §3 sent offline replays of up to 100 updates per message, about 10 KB | Medium | **Fixed.** Replays use the REST batch endpoint, which stays in V2 for that purpose; live updates stay on the socket ([LLD §14.2](low-level-design.md#142-messages)) |
| R-08 | Realtime | A rider connected before booking had no channel on which to learn that a driver was assigned: only `drv:{id}` and `ride:{id}` existed | Medium | **Fixed.** A personal channel `rdr:{riderId}`; seeing an active `ride_status` there subscribes the node to `ride:{id}` ([LLD §14.3](low-level-design.md#143-channels-and-subscriptions)) |
| R-09 | Modules | Two dependency cycles: ride ↔ payment (dues check; charging on completion) and ride ↔ dispatch (assignment; cancellations releasing drivers) | Medium | **Fixed.** Events cross modules as JSON contracts, and ride declares `RideDispatchParticipant` for dispatch to implement ([ADR-019](decisions/ADR-019-module-layout-and-boundaries.md)) |
| R-10 | Events | V1–V2 deliver events in process, inline in the relay. A consumer calling the payment provider would stall every event behind provider latency | Medium | **Fixed.** Consumers only write to the database; a separate executor calls the provider outside transactions ([LLD §5.3](low-level-design.md#53-consumers-and-the-inbox), [§11.2](low-level-design.md#112-the-executor-worker-role)) |
| R-11 | Payments | A crash during a provider call left the attempt's outcome unknown, with a risk of a blind resend | Medium | **Fixed.** Attempts are `IN_FLIGHT` under a lease; an expired lease means `UNKNOWN` and status checks, never a resend |
| R-12 | Timers | A search-timeout timer from before a reassignment could end the new search early | Medium | **Fixed.** Timers carry the ride's search generation ([LLD §7.3](low-level-design.md#73-search-timeout-t3)) |
| R-13 | Security | One-time codes queued for SMS delivery would have sat in the database in plain text | Medium | **Fixed.** Only an HMAC of the code is stored, and the SMS is sent synchronously ([LLD §12.1](low-level-design.md#121-one-time-codes)) |
| R-14 | Privacy | PINs and phone numbers could leak through events, pushes, logs or the audit log | Medium | **Fixed.** Explicit rules, and schemas without such fields; the PIN appears only in the rider's HTTPS view ([LLD §12.5](low-level-design.md#125-personal-data)) |
| R-15 | Security | Refresh rotation with reuse detection signs users out when a refresh response is lost on a flaky mobile network | Low | **Fixed.** A 10 s grace window for retries ([LLD §12.2](low-level-design.md#122-tokens)); [ADR-015](decisions/ADR-015-identity.md) amended |
| R-16 | Data growth | Quotes kept 30 days grow by ~26 million rows a day at the cloud tier (5–10 quotes per booking), most never used | Medium | **Fixed.** Unused quotes are deleted 24 h after expiry; used quotes are kept 30 days |
| R-17 | Events | Offer and payment events are ordered per ride in Kafka, but the relay keyed messages by aggregate ID | Low | **Fixed.** A `partition_key` column on the outbox ([LLD §4.1](low-level-design.md#41-platform)) |
| R-18 | V1 shape | V1's in-memory live index is only correct when all roles run in one process | Low | **Accepted.** Startup refuses other role splits; V2 replaces it; one contract suite covers both implementations |
| R-19 | Scope | Requirements V3 names "tracking fan-out" and "demand and supply counters" consumers. The design provides both without Kafka: pub/sub for tracking, the database and live index for surge | Low | **Accepted.** Surge keeps working while Kafka is down ([LLD §19.1](low-level-design.md#191-v3-kafka)) |
| R-20 | Throughput | One relay under a lease caps event throughput | Medium | **Tracked** to V3: spike S-4, with outbox partitioning ready (ADR-008) |
| R-21 | Capacity | One PostgreSQL primary takes every write; the designed-for tier needs ~9,000 writes/s | Medium | **Tracked** to V6: partitioning by city group, a read replica |
| R-22 | Capacity | Each city's live index runs on one Valkey thread, and `GEOSEARCH` cost grows with density | Low | **Tracked:** 0.19 ms at cloud density (S-1); a smaller first radius or a split city if a denser city needs it |
| R-23 | Operations | Key rotation procedure, runbooks, dashboards and alert tests aren't written yet | Low | **Tracked** to V7 |
| R-24 | Dependencies | Lettuce re-subscribes sharded pub/sub channels after a reconnect only from 6.4.1/6.5 | Low | **Tracked** to V2 phase 13: Lettuce 6.5 or later and a 3-shard cluster test ([ADR-020](decisions/ADR-020-valkey-access.md)) |
| R-25 | API | "Every state-changing `POST` requires an `Idempotency-Key`" would have stored token responses for sign-in, handing the same tokens to anyone who replayed the key, and forced keys on quotes and location updates | Low | **Fixed.** Keys on commands that change rides, offers, driver status, payments and ratings; the rest are exempt, each with its own protection ([LLD §5.1](low-level-design.md#51-idempotency-adr-009)); [ADR-009](decisions/ADR-009-idempotency-and-identifiers.md) amended |

## 3. Single points of failure

| Component | Effect of losing it | Mitigation | What remains |
|---|---|---|---|
| PostgreSQL primary | Commands answer `503` during a Multi-AZ failover (~1–2 min); tracking continues on Valkey | Clients retry with the same idempotency key; tasks and timers resume where they stopped | Booking unavailable for the failover; a few failovers a month fit in the 99.9% budget (43 min) |
| A city's Valkey shard | Matching and pushes pause | Replica promotion; positions refill within 4 s; reconciliation at once on an empty city; the sweeper's safety valve | Matching back within 10 s (NFR-7) |
| The outbox relay | Side effects pause; rides continue | Lease takeover within 10 s | Notifications and charges a few seconds late |
| Kafka (V3+) | Side effects pause; rides continue | The outbox buffers | Late side effects |
| Routing provider (V4) | Quotes and ETAs fall back to the mock | Timeouts and fallback | Less accurate ETAs |
| Payment provider | Charges become `UNKNOWN`, then dues | Status checks and webhooks | Rides never affected |
| The region | Everything stops | Multi-region is designed, not built (HLD §13.4) | Accepted (A-5) |

## 4. First bottleneck per tier

| Tier | First limit | Answer |
|---|---|---|
| Laptop (2,000 drivers) | Memory in the 4 GB container VM | One process, Compose profiles, Kafka's native image (HLD §16.1) |
| Cloud test (50,000) | WebSocket memory per node (~21 KB per connection on tuned Tomcat) | Four `realtime` nodes of ~0.75 GB (S-3) |
| Designed for (500,000) | PostgreSQL write volume; per-city Valkey command time; relay throughput | Partitioning by city group (V6); a shard per city (ADR-004); outbox partitions (S-4, V3) |

## 5. Threats (STRIDE)

| Threat | Example | Control |
|---|---|---|
| Spoofing | A stolen refresh token; a forged webhook; fake GPS | Rotation with reuse detection; HMAC-signed webhooks with a time window; accuracy and implausible-speed rules, logged for review |
| Tampering | A driver making itself available; a client-side fare | Availability comes from the server's mirror, never the app; fares are priced on the server and fixed in immutable quotes |
| Repudiation | "I never cancelled that ride" | Transition log with actor, time and request ID; append-only audit log |
| Information disclosure | Tracking a driver outside a ride; reading another rider's ride; phone numbers | The ride channel exists only during a ride; ownership checks answer `404`; no phone numbers in events, pushes or logs |
| Denial of service | Quote scraping, code bombing, WebSocket floods | Rate limits per user, IP and phone; one-time codes fail closed; 1 KB frames and per-session send limits; AWS WAF |
| Elevation of privilege | A rider calling driver endpoints; a driver reading other rides | Roles checked by Spring Security, ownership in application services, and a test per endpoint for both |

## 6. Complexity

| Choice | Simpler alternative | Why the design keeps it |
|---|---|---|
| 14 modules for one developer | Fewer, larger modules | Boundaries are both the learning goal and the extraction path; Spring Modulith checks them for free |
| Four runtime roles | One role | Locally it *is* one process; roles only separate in the cloud, where location and API traffic scale differently |
| Kafka from V3 | Stay with the in-process outbox | The location stream and replay need a log; the core flow never depends on it |
| Search tasks and timers in PostgreSQL | A workflow engine | Fewer moving parts, measured in S-2, and transactional with the state they guard |
| In-memory live index in V1, Valkey in V2 | Valkey from V1 | Keeps ADR-001's promise that V1 runs on PostgreSQL alone; the same contract suite covers both |

Deliberately thin: payments (no ledger), one region, no machine learning (requirements §9).

## 7. Document changes made by this review

- **HLD:** lock-order rule (§5.1); `rdr:` channel and the city-wide position set (§5.2); quote retention (§5.5, Appendix B); event catalog consumers (§9.4); idempotency-key scope (§10.1); endpoints and WebSocket messages (§10.2–§10.3); status set to approved.
- **Ride lifecycle §1:** lock-order rule.
- **Dispatch §2:** availability rows kept while offline; only seen offers count towards going offline.
- **Location §3–§4:** replays over REST; the sweeper's safety valve.
- **ADR-006, ADR-009, ADR-010, ADR-015:** dated amendments. **ADR-019 to ADR-022:** new.

## 8. Open items for implementation

| Item | Settled in |
|---|---|
| Testcontainers settings for Colima on macOS 13 | Phase 1 |
| RDS support for PostgreSQL 18 with PostGIS 3.6 (fallback: 17, ADR-003) | Before V7 |
| OSRM memory and build time for the Bengaluru extract | Phase 15 |
| Spike S-4: relay throughput and ordering under failover | Start of V3 |
