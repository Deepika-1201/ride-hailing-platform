# ADR-001: Modular monolith with an event-driven core and runtime roles

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [Requirements](../requirements.md) §2, §7, §8

## Context

- The system has two data planes with opposite needs. Location updates arrive at ~12,500/s at the cloud tier; each is worthless 4 s later and losing a few is harmless. Rides, offers and payments arrive at ~30 bookings/s, and each must be exactly right. Dispatch reads the first plane to find candidates and commits through the second.
- The core invariant, a driver is never assigned to two rides, spans two aggregates: the ride and the driver's availability.
- One developer, an 8 GB laptop with a 4 GB container VM, and a learning goal centred on real-time distributed systems.
- The sibling projects are modular monoliths; the Payment Orchestrator runs `api` and `worker` roles from one image.

## Problem

How should the system be divided into deployable units at the start, so that it is correct and understandable, the location path scales separately from the API, and services can be split out later without a rewrite?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Modular monolith, synchronous, no broker | Fewest moving parts; assignment is one local transaction; easiest debugging | Location traffic scales with the API; postpones the event-driven and streaming work this project exists to teach |
| **B. Modular monolith with an event-driven core and runtime roles** | One codebase and image; roles scale separately; assignment stays one local transaction; outbox, broker, idempotent consumers and partitioning are all exercised for real | Asynchronous hops make debugging and testing harder than A; roles share one database, so a runaway role can hurt the others |
| C. A few services along technical seams (realtime gateway, core, payments, notifications) | Independent deploys and failure isolation for the realtime edge | ~8 local processes on an 8 GB laptop; contract tests and cross-service tracing from day one; benefits unproven until load is measured |
| D. One microservice per bounded context (~12) | Maximum independence | Assignment becomes a cross-service saga; 20+ local processes; most effort goes into infrastructure instead of the real-time problems |

## Decision

1. **One codebase, one container image, four runtime roles**, enabled by configuration:
   - `api`: REST for riders, drivers, operations and admins, including quotes and bookings.
   - `realtime`: WebSocket connections for drivers (location updates in, offers out) and riders (tracking and status), plus location ingestion. From V2; in V1, location arrives over REST through `api`.
   - `dispatch`: candidate search, ranking, offers, and offer and search timers.
   - `worker`: outbox relay, event consumers (notifications, tracking fan-out, demand and supply counters), payment calls and housekeeping such as retention.

   Locally one process runs all four roles, to fit the 8 GB machine. In the cloud each role is a separate service started from the same image.
2. **Modules follow the bounded contexts** in the requirements: identity, rider, driver, location, dispatch, ride, pricing, payment, notification, rating, geography, operations and audit. A module owns its tables in its own PostgreSQL schema and exposes a Java API and domain events. No module reads or writes another module's tables, and tests enforce this.
3. **One sanctioned cross-module transaction.** Assigning a driver changes the ride (Ride module) and the driver's availability and offer (Dispatch module) in a single local transaction, with Dispatch calling the Ride module's API inside its own transaction. This is why Ride and Dispatch stay in one deployable until a measured reason says otherwise.
4. **Data stores.** PostgreSQL is the source of truth for everything except live positions. Redis holds the live location index from V2, and a broker carries domain events and the location stream from V3. Each gets its own ADR.
5. **Events.** Every state change writes its domain events to an outbox in the same transaction. Until V3 they are delivered in-process; from V3 a relay publishes them to the broker. All consumers are idempotent.
6. **Extraction path (V5).** A role or module leaves the monolith only with a measured reason. The first candidate is `realtime` with location ingestion, which scales with connections and messages rather than requests; notifications come next. Ride and Dispatch stay together.

## Trade-offs

- Roles share one database and one release. A bug in one role ships to all of them, and a busy role can take database connections from the others; per-role connection pools and limits contain this.
- Asynchronous delivery needs tests for duplicated, delayed and reordered events that option A would not need.
- Running all roles in one local process hides the network between them. Integration tests and the cloud tier run roles as separate processes to cover that gap.

## Consequences

- The core invariant rests on PostgreSQL constraints and conditional updates inside one transaction; no distributed lock is needed.
- The `realtime` role holds no state except its open connections, so any node can be drained or replaced. How a message reaches the node holding a driver's connection is settled with spike S-3.
- Module boundaries must be strict from the first commit, or extraction later becomes a rewrite.
- Redis and the broker sit behind interfaces, so V1 runs on PostgreSQL alone.

## Revisit when

- Measurements show the `realtime` role needs its own scaling, release cadence or failure isolation (V5).
- One PostgreSQL primary can no longer carry the transaction writes of the busiest city.
- More than one team works on the code.
