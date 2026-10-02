# ADR-017: OpenTelemetry everywhere, with SLOs and tested alerts

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [HLD](../architecture.md) §15; requirements NFR-2–NFR-5, NFR-11, NFR-12; [ADR-008](ADR-008-outbox-and-events.md), [ADR-009](ADR-009-idempotency-and-identifiers.md)

## Context

- A ride crosses HTTP, the outbox, Kafka (from V3), Valkey pub/sub, WebSockets and timers. Debugging "why did this ride take 40 s to get a driver?" needs one trace and one correlation ID across all of them.
- The SLOs in NFR-2–NFR-5 need measured indicators and alerts that fire on budget burn, not on single spikes.
- Locally, observability has to fit next to everything else in a 4 GB VM, and start only when needed.
- Both sibling projects use OpenTelemetry, Prometheus and Grafana, and test their alert rules with `promtool`.

## Problem

How are logs, metrics and traces produced, carried across asynchronous hops and turned into alerts?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. OpenTelemetry SDK (Spring Boot starter) → OTLP collector → Prometheus, Tempo, Loki, Grafana** | Vendor-neutral; same as the sibling projects; one all-in-one image locally | Several components in the cloud |
| B. Micrometer and Prometheus only | Simple metrics | No distributed traces |
| C. A commercial APM agent | Fast setup | Cost; lock-in; doesn't teach the mechanics |

## Decision

1. **Instrumentation:** Spring Boot's OpenTelemetry starter for traces and metrics; structured JSON logs carrying `trace_id`, `span_id`, `request_id` and `correlation_id`.
2. **Context propagation:** W3C trace context over HTTP; stored on outbox rows and copied into Kafka headers; included in offer messages over WebSocket; span links from a timer's creation to its firing.
3. **Metrics:** RED per endpoint plus the domain metrics in [HLD §15](../architecture.md#15-observability). Labels are limited to city, category, status and outcome. Ride, driver and zone IDs never become labels.
4. **SLOs:**
   - booking availability 99.9%;
   - booking and driver command latency p99 ≤ 300 ms;
   - first offer p95 ≤ 2 s;
   - location freshness p95 ≤ 1 s.

   Multi-window burn-rate alerts, plus platform alerts: outbox age, overdue timers, consumer lag, no-driver-found rate, payment failures, Valkey latency. Alert rules are tested with `promtool test rules`.
5. **Back ends:**
   - locally: the `grafana/otel-lgtm` all-in-one image in the `observability` Compose profile;
   - AWS: managed equivalents chosen at V7 (ADR-018).
6. **The operations timeline** (FR-O1) is built from transition logs, dispatch decisions and events, so a ride can be explained without searching logs.

## Trade-offs

- Tracing every location update would be wasteful: updates are sampled (1% **(assumed)**), while commands, offers and events are traced fully.
- Strict label rules mean per-zone or per-driver questions are answered from logs and traces, not dashboards.

## Consequences

- Each SLO has an indicator measured where it matters, for example booking-to-first-offer inside dispatch, and receive-to-publish for location.
- Dashboards and alert tests ship with the features they observe.

## Revisit when

- Telemetry cost or volume in AWS calls for tail-based sampling at the collector.
