# ADR-019: Package-per-module layout, verified boundaries and per-module migrations

- **Status:** Proposed
- **Date:** 2026-10-02
- **Related:** [LLD](../low-level-design.md) §1–§2; [ADR-001](ADR-001-architecture-style.md), [ADR-002](ADR-002-java-spring-boot-jdbc.md), [ADR-008](ADR-008-outbox-and-events.md); requirements NFR-15

## Context

- Fourteen modules live in one codebase and one image (ADR-001, [HLD §4.1](../architecture.md#41-modules)). ADR-002 requires tests to enforce their boundaries: a module may use another module's public API only, never its internals or tables.
- Extracting a module later (V5) should mean moving its code, its schema and its migration history together, with nothing left to untangle.
- Two pairs of modules want each other:
  - **ride and payment:** booking checks the rider's dues (ride → payment), and payment charges rides when they complete (payment → ride's events).
  - **ride and dispatch:** dispatch assigns drivers to rides (dispatch → ride), and ride transitions such as a rider's cancellation must withdraw offers and release drivers in the same transaction (ride → dispatch).
- The E-commerce sibling uses the same stack with Spring Modulith's boundary verification and one Flyway history per module.

## Problem

How is the code laid out, how are module boundaries and dependency directions enforced, and how do database migrations follow module ownership?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. One Gradle project, a package per module; Spring Modulith verification plus ArchUnit rules; a Flyway history per module** | One build; boundaries, cycles and allowed dependencies checked on every build; module documentation generated from the code; the same conventions as the E-commerce sibling | Violations compile and fail only at test time; a small custom Flyway set-up |
| B. A Gradle subproject per module, split into API and implementation | Boundaries enforced by the compiler | ~28 build modules for one developer; slower builds; much more build configuration |
| C. Hand-written ArchUnit rules only | No extra library | Re-implements what Spring Modulith already verifies (internal access, cycles, declared dependencies) |
| D. Java modules (`module-info.java`) | Strong encapsulation in the JVM | Friction with Spring's reflection, test tooling and split packages |

## Decision

1. **Layout.** One Gradle project with root package `com.ridehailing`. Each direct sub-package is a module. A module's **base package is its API**: interfaces, records, enums and service-provider interfaces other modules may use. Its sub-packages are internal: `domain`, `app`, `db`, `web` and `jobs`.
2. **Allowed dependencies** are declared in each module's `package-info.java` and verified by Spring Modulith (`ApplicationModules.verify()`), which also rejects cycles and access to internal packages. Only `spring-modulith-api` and the test support are used; Spring Modulith's event publication registry is not (ADR-008).
3. **Two rules keep the dependency graph acyclic:**
   - **Events cross modules as JSON contracts**, never as Java types. Producers own the JSON Schema; consumers map the payload into their own records. This is also exactly what Kafka consumers will see from V3.
   - **Ride declares a service-provider interface for dispatch.** When a ride transition has to change dispatch state in the same transaction (withdraw an offer, release a driver, start a search), the ride module calls `RideDispatchParticipant`, declared in its API and implemented by dispatch. Dispatch depends on ride; ride depends on no dispatch type.
4. **Schema ownership.** Each module owns one PostgreSQL schema named after it. No foreign key crosses schemas. A test scans every migration and every SQL string in a module and fails if it names another module's schema.
5. **Migrations per module.** Each module's migrations live in `db/migration/<module>/` with their own history table, `<module>.flyway_schema_history`. Startup migrates `platform` first, then the other modules in dependency order.
6. **ArchUnit coding rules** sit beside the module check: constructor injection only; no `System.out` or `java.util.logging`; no JPA; transactions start in application services, never in controllers or repositories; every HTTP controller and background job declares the runtime role it belongs to.

## Trade-offs

- A boundary violation compiles and is caught by the test suite rather than the compiler. The suite runs on every build, locally and in CI.
- JSON-contract events cost a small mapping class in each consumer. In exchange, a consumer can't accidentally depend on a producer's internals, and V3 changes only the transport.
- The participant interface makes one runtime call path (ride → dispatch) invisible in the static dependency graph. It is named, documented and covered by the assignment and cancellation tests.

## Consequences

- Extracting a module means moving its package, its migration folder and its history table.
- The module structure and its allowed dependencies can be rendered as diagrams from the code and compared with [LLD §2](../low-level-design.md#2-modules-and-dependencies).
- Every module has its schema and history from the first migration, before it has tables.

## Revisit when

- Several developers work on the code and compile-time boundaries become worth a multi-project build (option B).
- A module is extracted (V5); its JSON contracts and migrations move with it unchanged.
