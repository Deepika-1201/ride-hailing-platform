# ADR-002: Java 25 and Spring Boot 4.1, with plain JDBC and Flyway

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [Requirements](../requirements.md) Q8, C-3; [ADR-001](ADR-001-architecture-style.md)

## Context

- The system is a modular monolith with four runtime roles (ADR-001). Its core is rich domain logic: state machines, versioned fare rules, pluggable matching strategies and guarded state changes.
- The `realtime` role holds tens of thousands of WebSocket connections per node.
- One developer. The Payment Orchestrator uses Java 25, Spring Boot 4.1, plain JDBC, Flyway, OpenTelemetry and ArchUnit; the Job Scheduler uses Go.

## Problem

1. Which language and framework?
2. How does the code talk to PostgreSQL?

## Options considered

### Language and framework

| Option | Pros | Cons |
|---|---|---|
| **A. Java 25 + Spring Boot 4.1** | Records, sealed types and pattern matching fit state machines and domain events; virtual threads make blocking I/O cheap; mature support for PostgreSQL, Valkey, Kafka, WebSockets, OpenTelemetry and Testcontainers; same stack as the Payment Orchestrator | JVM memory on an 8 GB laptop; slower startup than Go |
| B. Kotlin + Spring Boot | Null safety, concise code, coroutines | A new language for one developer; mixed idioms with Java libraries |
| C. Go | Small memory per connection; single binary; already used in the Job Scheduler | No sum types for domain modelling; more hand-written plumbing; a second Go project adds less breadth |
| D. Node.js / NestJS | Fast I/O, quick start | Weak fit for CPU-bound matching; types erased at runtime |

### Database access

| Option | Pros | Cons |
|---|---|---|
| **A. Plain JDBC (`JdbcClient`) + Flyway** | Every guarded state change is one visible statement (`UPDATE … WHERE state = ? AND version = ?`); no hidden flushes; same as both sibling projects | More mapping code |
| B. JPA / Hibernate | Less boilerplate for CRUD | Dirty checking and flush order hide when and how rows change, which makes conditional updates and locking harder to reason about |
| C. jOOQ | Type-safe SQL | Code generation in the build |

## Decision

1. **Java 25 (LTS) with Spring Boot 4.1**, built with Gradle (Kotlin DSL), on the same versions as the Payment Orchestrator. REST endpoints use Spring MVC on virtual threads.
2. **Plain JDBC through Spring's `JdbcClient`**, with Flyway migrations and one schema per module (ADR-001). State changes are conditional updates whose affected-row count is checked.
3. **ArchUnit tests enforce module boundaries**: a module may use another module's public API package only, never its internals or tables.
4. The WebSocket stack for the `realtime` role is chosen separately, from spike S-3.

## Trade-offs

- The JVM costs memory: each role needs a few hundred MB. Locally all roles share one process (ADR-001), which keeps the footprint to one JVM.
- Hand-written SQL and row mapping take more code than JPA, in exchange for state changes that can be read and reviewed directly.

## Consequences

- Concurrency control is explicit in SQL: version columns, conditional updates, unique and partial unique indexes, `FOR UPDATE SKIP LOCKED`.
- Conventions, build layout and test tooling can be copied from the Payment Orchestrator.
- Testcontainers needs the local container runtime (Colima), as recorded in the requirements.

## Revisit when

- Memory per realtime connection on the JVM proves too high for the target number of connections per node (spike S-3, then V5 measurements).
- A component needs a different runtime profile, for example a separately deployed realtime gateway, where Go becomes a candidate.
