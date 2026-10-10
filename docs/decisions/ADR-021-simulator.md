# ADR-021: A Go simulator that drives the platform through its public APIs

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [LLD](../low-level-design.md) §18; requirements FR-S1–FR-S5, NFR-14, §7; [ADR-013](ADR-013-routing-provider.md); [HLD §17](../architecture.md#17-testing-strategy)

## Context

- From V2 the simulator is how this project generates realistic load and tests behaviour: thousands of drivers moving along Bengaluru roads, rider demand by zone and hour, offer acceptance and response times, cancellations, and faults such as disconnects and out-of-order updates (FR-S2–FR-S4).
- Runs must be reproducible from a seed and report rider wait times, pickup distances, match rate and cancellation rate (FR-S5).
- It runs on the 8 GB laptop beside the application JVM, PostgreSQL and Valkey: 2,000 drivers with WebSockets, plus riders (laptop tier).
- It should exercise the real code paths, so it may use only the public REST and WebSocket APIs (HLD §2).
- The spike benchmark (`spikes/bench`) is a Go program that already speaks to the WebSocket server.

## Problem

1. Which language and structure?
2. Where do realistic road movements come from?
3. What does "reproducible" mean for a simulator talking to a live system?

## Options considered

### Language

| Option | Pros | Cons |
|---|---|---|
| **A. Go** | Goroutines make one agent per driver and rider cheap (~50 KB each with its socket); a single static binary; a client written independently of the server tests the public contract honestly; reuses the spike's WebSocket client code | A second language in the repository |
| B. Java with virtual threads | One language; could reuse request and response types | Reusing server types defeats contract testing; a second JVM on an 8 GB laptop |
| C. k6 (JavaScript) | Built for load testing; good reports | Awkward for long-lived agents with state machines and seeded decisions; kept for API load scenarios instead |
| D. Python (Locust) | Quick to write | Thousands of WebSockets and timers strain one process |

### Road movement

| Option | Pros | Cons |
|---|---|---|
| **A. OSRM routes, recorded for replay** | Real roads for any origin and destination; a recorded run replays without OSRM, so tests and CI don't need it | OSRM (~1 GB **(assumed)**) runs during live simulator runs from V2, two versions before the application uses it (V4) |
| B. A precomputed library of fixed routes | No routing at run time | Can't route a driver from wherever it is to an arbitrary pickup |
| C. The simulator's own road graph built from the OpenStreetMap extract, with A* | No external process | A routing engine to write and maintain |
| D. Straight lines | Trivial | Drivers drive through lakes; misleading ETAs and maps |

## Decision

1. **Go, in `simulator/`**, its own Go module, using only the public REST and WebSocket APIs, with the same retry and idempotency-key rules as a real client (HLD §12.2).
2. **Agents:** one goroutine per driver and per rider, each a small state machine.
   - Drivers: shift start and length, initial position, offer acceptance probability by pickup distance, response time distribution, cancellation and no-show behaviour.
   - Riders: quote, book, wait, cancel if the wait exceeds their patience, ride, rate.
   - The rider agent hands the ride PIN to its driver agent inside the simulator, standing in for the conversation at the kerb.
3. **Roads:** a `Router` interface with three implementations:
   - OSRM over HTTP, from the `routing` Compose profile;
   - a recorded-route file (`--record` writes every route a run used; `--routes` replays them without OSRM);
   - straight lines, for smoke tests only.
4. **Demand:** Poisson arrivals per H3 resolution-7 zone and 15-minute slot, from a scenario file: hourly weights per zone, a destination matrix between zones, and events that multiply demand in a zone for a time window (the 10× hotspot test, requirements §7).
5. **Reproducibility:** a run seed derives one seed per agent. The same scenario, seed and recorded routes generate the same workload: the same requests at the same offsets, and the same decisions given the same server responses. Server timing still varies, so reports compare distributions, not exact numbers.
6. **Faults** are scheduled per scenario: disconnects, app restarts with resync, delayed, duplicated and reordered location updates, offline stretches replayed with `replay: true`, and skewed device clocks (FR-S4).
7. **Reports:** a JSON file and a text summary per run: wait-time and pickup-distance percentiles, match, cancellation and not-found rates, offers per ride. At the end of a run the simulator calls the operations invariant check (`GET /v1/ops/invariants`) and fails if it reports a violation.
8. **V1's scripted demo (FR-S1)** is not the simulator: it is `scripts/demo.sh` (curl and jq), which walks one ride through the REST API.

## Trade-offs

- Go adds a second toolchain (the spikes already use it). The simulator shares no code with the server, so request types are written twice; the OpenAPI document and JSON Schemas keep both honest.
- Live runs need OSRM from V2. Recorded runs don't, so CI never does.
- Reproducibility stops at the network: the same seed gives the same workload, not identical server timings.

## Consequences

- Load tests (V2, V6) and failure tests (V8) use the same agents with different scenario files.
- A routing-data set-up script builds the OSRM data for the Bengaluru extract once; the application reuses it in V4 (ADR-013).
- Contract drift shows up as simulator failures as well as contract-test failures.

## Revisit when

- Simulations need to run faster than real time, which would need simulated time on the server as well.
- Agent counts outgrow one machine (cloud tier, V6): run several simulator processes, each owning a slice of the agents.

## Amendment 2026-10-10: recorded smoke runs

- The CLI is `sim run|verify`; scenario expectations and invariant violations determine the exit code. Refresh-token caching avoids requesting thousands of codes on each rerun.
- A recording is keyed by endpoints rounded to five decimal places. Matching and idle movement still depend on server responses, so missing routes fall back to straight lines and are counted in the report. Replay validates client/server behavior without OSRM; it does not certify realistic routing or capacity.
- The CI scenario uses 40 drivers and 40 riders, four minutes of demand and a seven-minute drain. Larger scenarios retain their own workloads. The 2026-10-10 live and recorded runs each completed 17 rides with all eight invariants clean; the recorded run had 88 fallback routes. Phase 16 remains the laptop-tier performance gate.
- Osmium preprocessing renumbers local node IDs before clipping complete ways, avoiding a bitmap sized by the largest global OSM ID. The preprocessing and routing service use the same verified multi-platform OSRM image digest.
