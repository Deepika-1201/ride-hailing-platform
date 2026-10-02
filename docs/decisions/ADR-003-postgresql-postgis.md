# ADR-003: PostgreSQL 18 with PostGIS as the system of record

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [Requirements](../requirements.md) §6, §7; [ADR-001](ADR-001-architecture-style.md), [ADR-002](ADR-002-java-spring-boot-jdbc.md)

## Context

- Rides, offers, payments and audit records must never be lost or contradict each other (NFR-1, NFR-6). The core invariants (one active ride per driver, one live offer per driver) are best enforced by the database itself.
- Timers and outbox relays need queue-like claiming of rows by several workers.
- Geography needs real spatial types: city service areas, zones, airport geofences, trip routes.
- Write volume is modest: about 900 rows/s at the cloud tier and about 9,000/s designed-for (requirements §7).
- Both sibling projects use PostgreSQL 17.

## Problem

Which database is the source of truth, and which version?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. PostgreSQL 18 + PostGIS** | Transactions, partial unique indexes, `FOR UPDATE SKIP LOCKED`, JSONB, mature PostGIS; native `uuidv7()`; managed on AWS | One primary per database; scaling writes beyond it means partitioning |
| B. PostgreSQL 17 + PostGIS | Same as A, and identical to the sibling projects | No native `uuidv7()`; one release older |
| C. MySQL 8.4 | Widely run | Weaker spatial support; no partial indexes; `SKIP LOCKED` exists but the rest of the toolset is thinner |
| D. Distributed SQL (CockroachDB, YugabyteDB, Aurora DSQL) | Horizontal writes, multi-region | Higher latency per transaction, more operational surface, partial or no PostGIS; solves a scale problem this project won't reach before V6 |

## Decision

1. **PostgreSQL 18 with PostGIS 3.6** is the system of record for everything except live driver positions (those are chosen from spike S-1).
2. One database; one schema per module (ADR-001); UUIDv7 primary keys (NFR-17).
3. Invariants live in the database where possible: unique and partial unique indexes, check constraints, foreign keys within a module.
4. PostGIS is used for service areas, zones, geofences and trip-route queries, not for the live position of every driver.
5. Locally, the image is the official `postgres:18` image plus the PGDG PostGIS package, because the `postgis/postgis` images have no ARM64 build.

## Trade-offs

- PostgreSQL 18 is newer than what the sibling projects run, so their operational notes don't all carry over, and managed-service support must be confirmed before V7.
- A single primary caps write throughput. The capacity model puts the cloud tier well inside it; the designed-for tier needs partitioning by city.

## Consequences

- Concurrency control is plain SQL (ADR-002), and correctness tests can run against a real PostgreSQL through Testcontainers.
- Hot, high-churn data (live positions) stays out of PostgreSQL, where every update would create a dead row version.
- If managed PostgreSQL 18 with PostGIS 3.6 is not available on AWS at V7, falling back to 17 means generating UUIDv7 in the application; no other feature depends on 18.

## Revisit when

- One primary can't carry the busiest city's writes (designed-for tier).
- Active-active multiple regions becomes a requirement.
