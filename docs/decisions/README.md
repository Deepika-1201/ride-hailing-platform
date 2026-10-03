# Architecture Decision Records

Format: Context → Problem → Options considered → Decision → Trade-offs → Consequences → Revisit when. Status values: Proposed, Accepted, Superseded.

| ADR | Decision | Status |
|---|---|---|
| [ADR-001](ADR-001-architecture-style.md) | Modular monolith with an event-driven core and runtime roles (`api`, `realtime`, `dispatch`, `worker`) | Accepted |
| [ADR-002](ADR-002-java-spring-boot-jdbc.md) | Java 25 and Spring Boot 4.1, with plain JDBC, Flyway and ArchUnit-enforced module boundaries | Accepted |
| [ADR-003](ADR-003-postgresql-postgis.md) | PostgreSQL 18 with PostGIS as the system of record (not for live positions) | Accepted |
| [ADR-004](ADR-004-live-location-index.md) | Live driver positions in Valkey GEO sets, sharded by city (spike S-1) | Accepted |
| [ADR-005](ADR-005-durable-timers.md) | Durable timers in a PostgreSQL table, claimed with `SKIP LOCKED` (spike S-2) | Accepted (amended 2026-10-02) |
| [ADR-006](ADR-006-realtime-transport.md) | WebSockets for streams, HTTPS for commands, Valkey pub/sub to reach connections (spike S-3) | Accepted (amended 2026-10-02) |
| [ADR-007](ADR-007-message-broker.md) | Apache Kafka (KRaft) as the message broker, from V3 | Accepted |
| [ADR-008](ADR-008-outbox-and-events.md) | Transactional outbox, event envelope and versioning | Accepted (amended 2026-10-02) |
| [ADR-009](ADR-009-idempotency-and-identifiers.md) | Idempotency at every layer, and the identifiers that carry it | Accepted (amended 2026-10-02) |
| [ADR-010](ADR-010-state-machines.md) | Explicit state machines with versioned conditional updates | Accepted (amended 2026-10-02) |
| [ADR-011](ADR-011-dispatch-protocol.md) | Dispatch through search tasks in PostgreSQL, sequential offers reserved at offer time | Accepted |
| [ADR-012](ADR-012-pricing-and-zones.md) | Upfront quotes, versioned fare rules and H3 zones | Accepted |
| [ADR-013](ADR-013-routing-provider.md) | Routing-provider interface: a mock first, then self-hosted OSRM | Accepted (amended 2026-10-03) |
| [ADR-014](ADR-014-payments.md) | Thin payments: provider interface, realistic mock, charge after the trip | Accepted |
| [ADR-015](ADR-015-identity.md) | In-house phone sign-in with short-lived JWTs | Accepted (amended 2026-10-02, twice) |
| [ADR-016](ADR-016-trip-routes.md) | Trip routes in daily PostgreSQL partitions, archived to object storage at scale | Accepted |
| [ADR-017](ADR-017-observability.md) | OpenTelemetry everywhere, with SLOs and tested alerts | Accepted |
| [ADR-018](ADR-018-aws-deployment.md) | AWS: ECS Fargate in Mumbai, temporary environments, Terraform | Accepted (sizes at V7) |
| [ADR-019](ADR-019-module-layout-and-boundaries.md) | Package-per-module layout, verified boundaries and per-module migrations | Accepted |
| [ADR-020](ADR-020-valkey-access.md) | Valkey access: Lettuce, scripts by `EVALSHA`, sharded pub/sub | Accepted |
| [ADR-021](ADR-021-simulator.md) | A Go simulator that drives the platform through its public APIs | Accepted |
| [ADR-022](ADR-022-demo-web-app.md) | Demo web app: React and MapLibre on self-hosted OpenStreetMap tiles | Accepted |
| [ADR-023](ADR-023-recurring-jobs.md) | Recurring jobs scheduled by the expiry of a database lease | Accepted |
| [ADR-024](ADR-024-endpoint-access.md) | Endpoints declare who may call them; access is denied by default | Accepted |

Amendments are dated sections at the end of an ADR; the original decision text stays as it was.
