# ADR-022: Demo web app: React and MapLibre on self-hosted OpenStreetMap tiles

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [LLD](../low-level-design.md) §18; requirements FR-S6, FR-O5, A-2, C-4; [ADR-006](ADR-006-realtime-transport.md), [ADR-021](ADR-021-simulator.md)

## Context

- V2 adds a small web app: a live map of drivers and active rides for operations (FR-O5), and screens that let a person act as one rider and one driver among simulated ones (FR-S6).
- The map shows up to the laptop tier's 2,000 drivers moving every 4 s.
- No paid or key-based map services (C-4). OpenStreetMap data needs attribution wherever a map appears (A-2).
- The app must run locally with the rest of the stack and talk to the platform only through its public APIs: REST for commands and WebSocket for live updates (ADR-006).

## Problem

Which map library, which map tiles, and which front-end stack?

## Options considered

### Map library

| Option | Pros | Cons |
|---|---|---|
| **A. MapLibre GL JS** | Open source; WebGL rendering keeps thousands of moving points smooth as one GeoJSON source; vector tiles | WebGL and a style document to manage |
| B. Leaflet | Small and simple | DOM markers slow down with thousands of moving points; raster tiles |
| C. OpenLayers | Very capable | Larger API than a demo needs |

### Tiles

| Option | Pros | Cons |
|---|---|---|
| **A. A Protomaps PMTiles extract of Bengaluru, served as a static file** | No keys and no external service at run time; works offline; one file | A one-time download of a regional extract (tens of MB **(assumed)**), not committed |
| B. openstreetmap.org's tile servers | No set-up | The tile usage policy forbids heavy use; needs the internet |
| C. OpenFreeMap or another free hosted service | No set-up | An external dependency at run time |
| D. MapTiler, Mapbox, Google | Polished | API keys and usage-based pricing (C-4) |

### Front end

| Option | Pros | Cons |
|---|---|---|
| **A. React with TypeScript and Vite** | Mainstream; the portfolio's visualizer project uses React; typed messages | A build step |
| B. Plain TypeScript | Fewer dependencies | Hand-rolled state handling for three screens |
| C. Server-rendered pages | No client build | Live maps need client-side code anyway |

## Decision

1. **React, TypeScript and Vite** in `web/`, built to static files and served by nginx in the `simulator` Compose profile, beside the simulator.
2. **MapLibre GL JS** with a **Protomaps basemap**: a PMTiles extract clipped to the Bengaluru bounding box, downloaded by a set-up script and served from the same nginx. "© OpenStreetMap contributors" and Protomaps attribution are shown on every map.
3. **Three screens:**
   - **Operations:** live map of drivers coloured by status and of active rides, filtered by category; click a ride to open its timeline (FR-O1, FR-O5).
   - **Rider:** quote, book, see the PIN, track the driver, cancel, rate.
   - **Driver:** go online with a vehicle, move along a chosen route or follow the browser's location, answer offers, run the ride commands.
4. **Public APIs only:** sign-in with the fixed local one-time code, REST commands with idempotency keys, a WebSocket ticket per session, resync on every reconnect (ADR-006).

## Trade-offs

- The basemap extract must be downloaded once per machine and refreshed occasionally.
- A JavaScript build joins Gradle and Go in the repository; the app stays small and has no back end of its own.

## Consequences

- Demos and manual testing run fully offline once the tiles and routing data are prepared.
- The operations map needs a live snapshot of every online driver, not only available ones. LLD §9 adds a city-wide position set to the live index for it, and LLD §14 defines the snapshot message.

## Revisit when

- Operations needs more than a demo console, for example access control per city or historical replay; that would be a separate operations app.

## Amendment 2026-10-10: offline delivery and browser verification

- The web toolchain requires Node 22. MapLibre 6's worker is bundled through Vite; its default relative worker URL does not survive dependency bundling.
- PMTiles, map sprites and font files are served locally. Map labels use the bundled IBM Plex Sans files through MapLibre's `font-faces` property. The complete extract remains untracked; a 1.6 MB central Bengaluru zoom-12 fixture is committed solely for browser CI. OpenStreetMap and Protomaps attribution remain visible, with sprite and font licenses included in the static assets.
- Refresh tokens are cached per account in tab-scoped storage; access tokens remain in memory. Reloads rotate the cached token rather than requesting another OTP. Rating submission has a bounded same-key retry for the documented asynchronous `RATING_NOT_OPEN` response.
- The driver demo simulates straight-line movement at 30 km/h; it does not request real device geolocation. The Go simulator provides road-following movement. Default demo driver 1993 and rider 9001 are outside the CI simulator's 101-140 pools.
- Playwright exercises quote, book, offer acceptance, arrival, PIN start, completion, rating and the operations timeline against the real API. It checks map pixels and layout on desktop and mobile while blocking external HTTP requests. The same test passes against Vite and the nginx production image.
