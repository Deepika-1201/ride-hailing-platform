# ADR-013: A routing-provider interface: a mock first, then self-hosted OSRM

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [HLD](../architecture.md) §8; [dispatch design](../dispatch-design.md) §7; [location system](../location-system.md) §7; requirements C-4

## Context

- Quotes need route distance and duration. ETA ranking (V4) needs ETAs from many drivers to one pickup. Rider tracking needs a pickup ETA every ~15 s per active ride.
- Paid map APIs are out (C-4): cost, rate limits and usage terms. Everything must run locally on 8 GB.
- No part of the system should depend on one provider.

## Problem

Where do distances and travel times come from, in V1 and later?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. Mock: straight-line distance × detour factor ÷ zone speed by hour of day** | No dependencies; deterministic; good enough for V1–V3 | Ignores rivers, one-way roads and real traffic |
| **B. OSRM on an OpenStreetMap extract of Bengaluru** | Real road distances; fast table (matrix) and route services; free; runs in a container | Road speeds are static profiles, not live traffic; ~1 GB of memory for a city extract **(assumed)** |
| C. Valhalla | Richer costing, map matching | Heavier to build and run |
| D. Google Maps, Mapbox | Live traffic | Cost, rate limits, terms of use; external dependency |

## Decision

1. **`RoutingProvider` interface** with `route(from, to)` returning distance and duration, and `matrix(origins, destinations)` returning durations.
2. **V1–V3: `MockRoutingProvider`.** Straight-line distance × 1.35, divided by a zone speed for the hour of day (for example 18 km/h at peak in central Bengaluru) **(assumed)**. Deterministic, so tests and simulator runs repeat.
3. **V4: `OsrmRoutingProvider`.** OSRM in the `routing` Compose profile, built from a Geofabrik extract clipped to the Bengaluru area. Quotes use `route`; ETA ranking uses `table` for the ~10 nearest candidates.
4. **Caching:** results cached in Valkey by H3 resolution-9 cell pair for 10 min **(assumed)**. Calls have a timeout and fall back to the mock with a flag, so a routing outage degrades accuracy but never blocks quotes or dispatch.

## Trade-offs

- Mock ETAs are rough: ranking by straight-line distance in V1 is a known simplification, measured against ETA ranking in V4.
- OSRM has no live traffic, so peak-hour ETAs need time-of-day speed factors.

## Consequences

- The simulator can move drivers along OSRM routes in V2 by precomputing routes once, so realistic movement doesn't need OSRM running during every test.
- Switching providers is a configuration change per city.

## Revisit when

- ETA errors measurably hurt matching or quotes, for example with traffic-aware speeds or a commercial provider for a real deployment.

## Amendments

- **2026-10-03, phase 5** ([LLD §10.4](../low-level-design.md#104-creating-a-quote)):
  - `route` takes the departure time, so the mock's answers depend only on its inputs and repeat in tests; providers without time-of-day speeds ignore it. An empty answer means no route (`422 ROUTE_NOT_FOUND`).
  - In V1 the mock uses one speed profile by local hour for every zone (30 km/h at night down to 18 km/h at the peaks) **(assumed)**; speeds per zone wait until simulator runs show they matter.
  - `matrix` arrives with ETA ranking in V4; nothing before it needs more than one route at a time.

- **2026-10-12, phase 18 routing preparation:**
  - The OSRM route adapter remains opt-in while the V2/V3 measurement gates stay open. Requests use longitude/latitude order, preserve a configured base-path prefix, and suppress route geometry and steps.
  - A 300ms default deadline covers headers and response-body completion. Timeout, connection failure, invalid measurements and service errors use the existing mock with source `MOCK_FALLBACK`; OSRM's explicit `NoRoute` remains an empty result, not a fabricated road route.
  - Distance and duration are rounded to the nearest metre and second, consistent with the existing mock. OSRM durations represent its static car profile; time-of-day adjustment and matrix ranking are separate follow-up work. Responses are capped at 64KiB and redirects are not followed.
  - Activation uses `ride.routing.osrm.enabled=true` with an explicit, nonempty `cities` set. The origin's service-area city selects OSRM; other cities retain `MOCK`. Disabled mode creates no OSRM HTTP client. A missing Valkey instance allows uncached OSRM calls.
  - The route cache keeps only valid `OSRM` results, keyed by ordered H3 resolution-9 cell pairs and the configured OSRM endpoint. It expires after ten minutes; a cache read or write waits at most 20ms by default **(assumed)**. Cache errors, invalid entries, `NoRoute` and fallback results never poison successful road-route entries. The cell-pair approximation is intentional; changing routing data at the same endpoint requires waiting for expiry or clearing its cache namespace.
