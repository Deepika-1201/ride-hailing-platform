# ADR-007: Apache Kafka as the message broker, from V3

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [HLD](../architecture.md) §5.3, §9; [ADR-008](ADR-008-outbox-and-events.md), [ADR-011](ADR-011-dispatch-protocol.md); requirements NFR-7, §8 (V3)

## Context

- Two kinds of traffic need a broker:
  - **Domain events** (~10 per ride) feeding side effects: notifications, charges, ratings, earnings, the operations timeline, analytics.
  - **The location stream** (12,500 updates/s at the cloud tier) feeding trip-route archiving, demand and supply counts for surge, and analytics.
- Consumers need ordering per ride or per driver, replay (new consumers, incident recovery) and independent consumer groups.
- NFR-7 requires booking to keep working while the broker is down. So the core flow (book → dispatch → assign → complete) must not depend on the broker (ADR-011).
- V1 and V2 run without a broker: the outbox is delivered in-process (ADR-008). Neither sibling project runs a broker yet.

## Problem

Which broker carries events and the location stream from V3?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. Apache Kafka (KRaft)** | Per-key ordering through partitions; replay; consumer groups; a log suits a high-volume stream; Amazon MSK runs the same software; native image for light local use | Operating a cluster; no per-message delay or per-message acknowledgement; MSK costs money while running |
| B. Redpanda | Kafka-compatible; low memory | Business Source licence; the AWS target would still be MSK, so local and cloud would differ |
| C. RabbitMQ | Per-message acknowledgement and routing; good for work queues | Weaker replay and partitioned ordering; the stream use case fits a log better |
| D. SNS + SQS | Fully managed; cheap at low volume | No replay; ordering only in FIFO queues with throughput limits; at 12,500 location updates/s, per-request pricing (send, receive, delete) costs thousands of dollars a month even with 10-message batches |
| E. Valkey Streams | Already deployed | Memory-bound retention; Valkey holds only rebuildable data (ADR-004) |

## Decision

1. **Apache Kafka 4.x in KRaft mode** from V3.
   - Locally: the `apache/kafka-native` single-node image in the `kafka` Compose profile.
   - Testcontainers: the same image.
   - AWS: Amazon MSK, sized at V7 (ADR-018).
2. **Topics** follow [HLD §5.3](../architecture.md#53-kafka-from-v3): one per aggregate family, keyed by aggregate ID, plus `location.updates` keyed by driver ID, and a dead-letter topic per consuming group.
3. **Producers:**
   - Domain events: idempotent producer with `acks=all`, published by the outbox relay.
   - Location updates: `acks=1` with ~20 ms batching, because they tolerate loss.
4. **Consumers:**
   - One consumer group per consuming module.
   - Offsets committed after the database transaction that records the effect; the inbox makes redelivery harmless (ADR-009).
   - Three in-place retries, then the dead-letter topic.
5. **What stays out of Kafka:** timers (ADR-005), dispatch work (ADR-011) and pushes to clients (ADR-006).

## Trade-offs

- A second stateful system to run, test and pay for, justified mainly by the location stream and replay.
- No per-message delay or acknowledgement, which is why timers and dispatch tasks stay in PostgreSQL.
- `kafka-native` is meant for development and testing; it isn't a production image.

## Consequences

- From V3 the relay publishes the outbox to Kafka, and in-process consumers become Kafka consumers. The event envelope doesn't change (ADR-008).
- Spike S-4 (outbox relay throughput and per-ride ordering) runs when V3 starts.
- A Kafka outage delays notifications, charges and analytics, but not rides.

## Revisit when

- The location stream turns out not to be needed beyond trip routes and counters. SNS + SQS would then be cheaper for domain events alone.
- Windowed stream processing grows beyond simple counters; that is the point to consider Kafka Streams or Flink.
