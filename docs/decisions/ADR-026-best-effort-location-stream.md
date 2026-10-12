# ADR-026: Bound best-effort location publication outside the request path

- **Status:** Accepted
- **Date:** 2026-10-12
- **Related:** [ADR-007](ADR-007-message-broker.md), [Location system](../location-system.md) section 2; [LLD](../low-level-design.md) section 19.1

## Context

V3 adds an internal location stream with `acks=1`, 20ms linger and a 24-hour retention target. Live matching and tracking use the live index and push bus, not Kafka. Unlike domain events, location updates can be lost. Kafka's asynchronous producer can still block its caller while acquiring metadata or buffer space.

## Problem

How can applied positions reach Kafka without broker outages delaying driver acknowledgments or creating an unbounded memory backlog?

## Options considered

| Option | Benefits | Costs |
|---|---|---|
| Call Kafka `send` on the request thread | Simple; callback reports delivery | Metadata and buffer allocation can block the live path |
| Put every location in the transactional outbox | Durable retries | A database write per position; wrong durability and latency trade-off |
| Bounded background sender with explicit loss metrics | Isolates broker waits and caps queued work | Gaps during saturation, shutdown and outages |

## Decision

1. Only updates that the live index actually applied enter the stream. Carry the original fix, server receive time, device time, driver sequence, mirror state, quality flags and replay marker. Invalid, stale and offline updates do not enter the stream. Trip-point buffering stays independent.
2. An optional platform `EventStream` port accepts immutable values using a nonblocking queue offer. The location ingestion path never invokes Kafka or waits for delivery. Unexpected publication errors are counted and cannot reject an already-applied update.
3. One background sender per process serializes values and invokes Kafka. Queue capacity, record size, producer buffer memory, send timeout and shutdown wait are bounded. Initial defaults are 1,024 queued records, 2KiB per record, 1MiB producer buffer, 2s delivery timeout and 1s shutdown wait **(assumed; not capacity results)**.
4. Use `acks=1`, `linger.ms=20`, zstd, disabled idempotence, zero producer retries and one in-flight request per broker connection. Queue-full, oversized, failed and shutdown drops are metrics, not durable retry work. Reconnection allows later positions to resume.
5. Stopping detaches the sender before cleanup, rejects new offers and discards queued positions. Restart creates a fresh sender. A producer whose lazy construction finishes after shutdown is closed without publishing its stale work.
6. The stream is best effort, not an at-least-once delivery guarantee. Consumers must tolerate gaps, deduplicate driver sequences and ignore obsolete updates; concurrent ingestion may enqueue applied updates in a different order. Flagged fixes do not assert that the usable live position moved.
7. Idle drivers have no long-lived trip history, but their applied positions are present in the internal, 24-hour operational stream. It is not exposed to public clients; derived analytics retain zone counts rather than driver routes.

## Trade-offs

- A saturated or disconnected broker loses positions. Live matching, tracking and local trip buffering remain available.
- A process has a small bounded queue and lazy producer resources rather than sharing the durable outbox producer's acknowledgement policy.
- The configured retention target is not a claim of instantaneous physical deletion; Kafka segment and deletion scheduling still apply.

## Consequences

- Fault tests must block producer creation and sends, fill the queue, fail callbacks, and stop/restart the sender while ingestion remains responsive.
- A versioned location schema is separate from the domain-event envelope and its 22-type catalog.
- Throughput and loss rates still require measurement; this decision does not certify the laptop or cloud tier.

## Revisit when

- Measured drop rates make coalescing, partitioning or a different buffer policy worthwhile.
- A consumer requires durable location history; that requires a separate retention and delivery decision rather than silently strengthening this pipeline.