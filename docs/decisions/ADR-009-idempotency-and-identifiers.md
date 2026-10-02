# ADR-009: Idempotency at every layer, and the identifiers that carry it

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [HLD](../architecture.md) §9–§11; [ADR-008](ADR-008-outbox-and-events.md), [ADR-010](ADR-010-state-machines.md), [ADR-014](ADR-014-payments.md); requirements FR-RD7, FR-RD8, FR-PY1, FR-PY6, FR-L2

## Context

- Mobile clients retry on flaky networks, and drivers replay commands made offline.
- Event delivery is at least once (ADR-008); the payment provider times out with unknown outcomes; location updates arrive duplicated and out of order.
- A retried booking must not create two rides, and a retried charge must not charge twice.

## Problem

How does every layer make a repeat harmless, and which identifiers tie requests, events and traces together?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. Layered: API keys, guarded transitions, consumer inbox, provider keys, sequence numbers** | Each layer handles the duplicates it can see; mirrors the Payment Orchestrator | Several mechanisms to learn |
| B. API idempotency keys only | Simple | Doesn't cover redelivered events, provider retries or location updates |
| C. Dedupe only in the message layer | Central | Can't stop a client from re-sending a command |

## Decision

| Layer | Mechanism |
|---|---|
| HTTP commands | Every state-changing `POST` requires `Idempotency-Key`. `platform.idempotency_keys` stores key, principal, request fingerprint, response status and body for 24 h, **written in the same transaction as the change**. Same key and request: the stored response. Same key, different request: `422 IDEMPOTENCY_KEY_REUSED`. Same key still in progress: `409` with `Retry-After` |
| Domain transitions | Guarded by status and version (ADR-010). A command that already took effect is recognised (natural idempotency); a conflicting one gets `409` |
| Event consumers | `platform.inbox (consumer, event_id)` inserted in the same transaction as the consumer's effects; a duplicate insert means the event was already handled |
| External providers | Payment attempts use the attempt ID as the provider's idempotency key. Status checks replace retries after a timeout. Provider webhooks are deduplicated by provider event ID |
| Location updates | Per-driver sequence numbers checked in the Valkey update script; trip points unique per ride and sequence |
| Offline driver commands | The client-generated command ID is the idempotency key; the device time is stored too |

| Identifier | Created by | Used for |
|---|---|---|
| `Idempotency-Key` | Client, per logical command | Deduplicating retried commands |
| `request_id` | Edge (or the client's `X-Request-Id`) | One HTTP request; logs and error bodies |
| `correlation_id` | Booking: the ride ID; otherwise the request ID | Everything about one ride: logs, events, traces |
| `causation_id` | The command or event that caused an event | Reconstructing cause and effect |
| `event_id` | Outbox, UUIDv7 | Consumer deduplication |
| `trace_parent` | OpenTelemetry | Distributed traces across HTTP, outbox, Kafka and WebSocket |
| Sequence number | Driver app, per driver | Ordering location updates |

## Trade-offs

- Each state-changing request writes an idempotency row (24 h retention), and each consumer writes an inbox row per event.
- Clients must keep the same key across retries, which the simulator and demo app must implement deliberately.

## Consequences

- Retries are safe everywhere: client → API, relay → consumer, worker → provider, app → location index.
- Logs, traces, events and the operations timeline join on the correlation ID, which is the ride ID for every ride flow.

## Revisit when

- Keys need to live longer than 24 h, for example offline commands replayed days later; the retention then rises for that endpoint.

## Amendments

- **2026-10-02, low-level design** ([LLD §5.1](../low-level-design.md#51-idempotency-adr-009), [review](../architecture-review.md) R-25): keys are required on commands that change a ride, an offer, a driver's status, a payment or a rating, not on every state-changing `POST`. Sign-in and token calls are exempt because a stored response would hand the same tokens to anyone replaying the key; quotes because a repeat is just another quote; location updates, tickets and webhooks because sequence numbers, single use and provider event IDs already deduplicate them.
