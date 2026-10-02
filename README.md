# Ride-Hailing Platform

A ride-hailing and dispatch platform built as a real-time distributed system. Riders request rides, drivers stream their live locations, and the platform matches each request to a nearby driver within seconds, then manages the ride through an explicit state machine until the trip ends.

The focus is the real-time layer: ingesting a continuous stream of driver locations, finding nearby drivers while positions change thousands of times a second, and dispatching safely under concurrency, so that a driver is never assigned to two rides at once.

> **Status:** design phase. The requirements baseline and [ADR-001](docs/decisions/ADR-001-architecture-style.md) (architecture style) are done. Next come design spikes, then the high-level and low-level designs. Implementation starts once the design is approved.

## Documentation

| Document | Contents |
|---|---|
| [Requirements](docs/requirements.md) | Scope decisions, functional and non-functional requirements, policies, capacity model, delivery plan by version |
| [Decision records](docs/decisions/README.md) | One ADR per significant architecture decision |
