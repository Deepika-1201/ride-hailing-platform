# Architecture Decision Records

Format: Context → Problem → Options considered → Decision → Trade-offs → Consequences → Revisit when. Status values: Proposed, Accepted, Superseded.

| ADR | Decision | Status |
|---|---|---|
| [ADR-001](ADR-001-architecture-style.md) | Modular monolith with an event-driven core and runtime roles (`api`, `realtime`, `dispatch`, `worker`) | Accepted |
| [ADR-002](ADR-002-java-spring-boot-jdbc.md) | Java 25 and Spring Boot 4.1, with plain JDBC, Flyway and ArchUnit-enforced module boundaries | Accepted |
| [ADR-003](ADR-003-postgresql-postgis.md) | PostgreSQL 18 with PostGIS as the system of record (not for live positions) | Accepted |
| [ADR-004](ADR-004-live-location-index.md) | Live driver positions in Valkey GEO sets, sharded by city (spike S-1) | Accepted |
| [ADR-005](ADR-005-durable-timers.md) | Durable timers in a PostgreSQL table, claimed with `SKIP LOCKED` (spike S-2) | Accepted |
| [ADR-006](ADR-006-realtime-transport.md) | WebSockets for streams, HTTPS for commands, Valkey pub/sub to reach connections (spike S-3) | Accepted |
