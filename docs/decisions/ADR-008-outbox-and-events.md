# ADR-008: Transactional outbox, event envelope and versioning

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [HLD](../architecture.md) §9; [ADR-007](ADR-007-message-broker.md), [ADR-009](ADR-009-idempotency-and-identifiers.md), [ADR-010](ADR-010-state-machines.md)

## Context

- A state change and the event announcing it must not part ways: a ride marked `COMPLETED` without a `TripCompleted` event never gets charged, and an event for a rolled-back change charges a ride that didn't complete.
- Consumers need events for each ride in order, and must cope with duplicates.
- V1–V2 deliver events in-process; V3 publishes them to Kafka. The format must survive that change.
- Traces should continue across the asynchronous hop.

## Problem

How are events published reliably, in order per aggregate, in a format that can evolve?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. Transactional outbox with a polling relay** | Same transaction as the change; works without a broker (V1–V2) and with one (V3); explicit control of ordering | Polling delay (~100–250 ms); extra writes |
| B. Change data capture (Debezium) | Commit-order capture; no polling | Kafka Connect to run; no broker exists in V1–V2 |
| C. Publish after commit | Simple | A crash between commit and publish loses the event |
| D. Spring Modulith event publication registry | Built-in persistence and retry | Ordering and the envelope are less explicit; one more framework abstraction to learn |

## Decision

1. **Outbox:** `platform.outbox`, written in the same transaction as the state change, one row per event.
2. **Relay:**
   - Runs in the `worker` role under a PostgreSQL lease with a fencing token, so exactly one relay is active. This is the Job Scheduler's pattern.
   - Reads unpublished rows in ID order and marks each one published. It uses a published flag, not a high-water mark, so a transaction that commits late is never skipped.
   - Published rows are deleted after 7 days.
   - V1–V2: delivers to in-process consumers. V3+: publishes to Kafka keyed by aggregate ID.
3. **Ordering** holds per aggregate. Transitions of one aggregate are serialized by its version, so its outbox rows are committed in order, and the relay keeps that order. Every event carries `aggregate_version`.
4. **Envelope:** `event_id` (UUIDv7), `event_type`, `event_version`, `aggregate_type`, `aggregate_id`, `aggregate_version`, `occurred_at`, `producer`, `correlation_id`, `causation_id`, `trace_parent`, `payload`. Field meanings: [HLD §9.3](../architecture.md#93-event-envelope).
5. **Schemas:** each event type and version has a JSON Schema in the repository. Producer and consumer tests validate against it.
6. **Versioning:**
   - Optional additions keep the version.
   - A breaking change publishes `event_version + 1` alongside the old version until every consumer has moved; the old version is then retired with a note in the catalog.
   - Consumers ignore unknown fields.
7. **Consumers** deduplicate through `platform.inbox` (ADR-009) and treat delivery as at least once. No component claims exactly-once delivery.

## Trade-offs

- Each ride writes about ten outbox rows, roughly a third of the ~30 rows per ride in the capacity model.
- Events reach consumers 100–250 ms after commit because of polling. Nothing latency-critical depends on events: pushes go directly through Valkey (ADR-006).
- A single active relay caps throughput. Spike S-4 measures it in V3; partitioning the outbox by aggregate hash with one lease per partition is the next step.

## Consequences

- A crash at any point loses no event and creates no event for a rolled-back change.
- The format and guarantees stay the same when Kafka arrives in V3.
- The operations timeline and event replay can read the outbox within its retention.

## Revisit when

- S-4 shows the relay can't keep up: partition the outbox, or move to change data capture.
- A schema registry becomes worthwhile, for example with many external consumers.

## Amendments

- **2026-10-02, phase 2** ([LLD §5.2](../low-level-design.md#52-outbox-and-relay-adr-008), [§5.3](../low-level-design.md#53-consumers-and-the-inbox)):
  - Published rows are deleted after 7 days **unless a consumer's delivery of the event is still set aside**, because a re-drive reads the event from its outbox row.
  - A consumer that fails 3 retries has the event set aside in `platform.failed_deliveries` for that consumer only; other consumers and later events continue. `FailedDeliveries.redrive` delivers it again through the inbox.
  - The relay renews its lease between events and releases it on shutdown. Its "mark published" update checks holder and token in the same statement: the token alone tells a stale relay from its successor when a restarted container reuses the host name and process ID.
  - The envelope's `producer` is derived from the payload's module and the role in the logging context; `correlation_id` defaults to the partition key, and `causation_id` to the event being handled or else the request ID.
