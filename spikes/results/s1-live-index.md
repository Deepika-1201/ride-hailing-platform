# S-1: Live location index

**Question:** where should the live position of every online driver live so that "nearest available drivers of category X" is fast while positions change thousands of times a second? ([requirements §12](../../docs/requirements.md#12-open-questions-for-design-spikes))

**Answer:** Valkey GEO sets. At the cloud tier they used 0.26 CPU cores, against 1.4–1.5 for PostGIS, and kept query p99 under 9 ms. PostGIS's problem is row churn, not WAL. Decision: [ADR-004](../../docs/decisions/ADR-004-live-location-index.md).

## Setup

- Bengaluru: drivers clustered around seven hotspots (CBD, Koramangala, Whitefield, Electronic City, Manyata, Majestic, airport) plus a city-wide background. Categories auto/mini/sedan/XL at 40/35/15/10%. About 40% of drivers are available at any time, and they flip between available and busy at trip rates.
- Every driver sends an update every 4 s, moving with a persistent heading at 0–50 km/h. Queries ask for the 20 nearest **available, fresh** drivers of one category within 3 km of a pickup drawn from the same hotspots.
- When measurement starts, 2% of drivers go silent (phone died). A correct index must never return a driver not heard from for 30 s.
- Tiers from [requirements §7](../../docs/requirements.md#7-capacity-model): **laptop** 2,000 drivers (500 updates/s, 15 queries/s, 45 s measured); **cloud** 50,000 drivers (12,500 updates/s, 300 queries/s, 60 s measured).
- Servers: Valkey 9 and PostgreSQL 18 + PostGIS 3.6, each in a container inside a 4-vCPU Colima VM, with the load generator in the same VM. Server CPU comes from each container's cgroup.

| Approach | Writes per update | Query |
|---|---|---|
| `redis-geo` | Lua script: check sequence number, `HSET` driver hash, `GEOADD` to the category's GEO set if available (else `ZREM`), `ZADD` last-seen | Lua: `GEOSEARCH … BYRADIUS 3 km ASC COUNT 40`, drop members whose last-seen is older than 30 s, return 20. A sweeper removes silent drivers every 5 s |
| `redis-h3` | Lua: check sequence, `HSET` driver hash; `SREM`/`SADD` on the H3 cell set (resolution 8) only when the cell or availability changes; `ZADD` last-seen | Grow rings of cells around the pickup until 20 drivers are guaranteed found; for each candidate read its position from its hash, compute distances in the client |
| `postgis` | `UPDATE driver_location SET geog, seq, seen_at, available WHERE driver_id = ? AND seq < ?` | GiST index on `(category, geog) WHERE available`; `ST_DWithin` + `ORDER BY geog <-> point LIMIT 20` with a freshness filter |
| `postgis-unlogged` | Same table `UNLOGGED`, `synchronous_commit = off` | Same |

## Results: cloud tier (50,000 drivers)

| | Valkey GEO | Valkey + H3 cells | PostGIS | PostGIS unlogged |
|---|---|---|---|---|
| **Server CPU** | **0.26 cores** | 0.45 cores | 1.46 cores | 1.41 cores |
| Update latency p50 / p99 / max | 0.61 / 5.9 / 42 ms | 0.49 / 10.6 / 143 ms | 1.38 / 16.7 / 792 ms | 0.25 / 17.6 / 582 ms |
| Query latency p50 / p99 / max | 3.8 / 8.7 / 43 ms | 14.5 / 65.6 / 277 ms | 2.8 / 33.9 / 668 ms | 1.1 / 108.6 / 536 ms |
| Server time per query | 0.19 ms (`GEOSEARCH`) | ~0.7 ms (≈376 hash reads) | 2.3 ms | 3.3 ms |
| WAL | — | — | 3.7 MB/s (324 B per update) | 0.4 MB/s (34 B per update) |
| Dead rows at the end (50,000 live) | — | — | 331,797 | 593,946 |
| Memory / on-disk size | 13 MB | 13 MB | 51 MB and growing | 53 MB and growing |
| Stale drivers returned | 0 | 0 | 0 | 0 |

All four returned the same answers (19.7 drivers on average, 2.8% of queries short of 20), so the comparison is like for like. Latencies include a load generator competing for the same 4 vCPUs; server-side figures are the ones to plan with.

## Results: laptop tier (2,000 drivers)

| | Valkey GEO | Valkey + H3 cells | PostGIS | PostGIS unlogged |
|---|---|---|---|---|
| Server CPU | 0.03 cores | 0.03 cores | 0.11 cores | 0.09 cores |
| Update p50 / p99 | 0.54 / 4.0 ms | 0.49 / 2.5 ms | 1.28 / 5.3 ms | 0.46 / 3.5 ms |
| Query p50 / p99 | 0.94 / 5.9 ms | 3.7 / 9.2 ms | 0.85 / 4.0 ms | 0.78 / 8.7 ms |

At 2,000 drivers every option is fine; the city is sparse enough that 59% of queries find fewer than 20 drivers within 3 km.

## What the numbers say

1. **PostGIS loses to row churn, not to WAL.** Every position update writes a new row version, and only ~6% were HOT updates (those where the driver hadn't moved), because the position is indexed. Removing WAL (unlogged table, no synchronous commit) cut WAL by 90% but saved almost no CPU, and created *more* dead rows because updates got faster. After 80 seconds the table held 6–12 dead versions per live row, autovacuum had run once, and query time grew to 2–3 ms with tails above half a second. At designed-for rates (125,000 updates/s) this needs constant aggressive vacuuming; at the cloud tier it already costs 5–6× Valkey's CPU, and the logged variant would ship ~320 GB of WAL a day to replicas and backups for data that is worthless 4 s later.
2. **H3 cell sets make updates cheap and queries expensive.** Most updates skip the set operations because the driver stays in the same cell. But a query has to read the position of every driver in the rings it covers, ~376 hash reads per query at cloud density, and often needs several round trips as rings grow. H3 stays useful for zones (surge, demand and supply counts, partitioning), not for nearest-driver search.
3. **Valkey GEO is cheapest and simplest.** `GEOSEARCH` cost scales with the drivers inside the search area (0.02 ms at laptop density, 0.19 ms at cloud density), not with the city total. One update script and one query script, both single round trips.
4. **Silent drivers must be handled explicitly.** GEO members can't expire individually. The last-seen check inside the query script kept stale drivers out of every answer, and the 5 s sweeper removed all 1,000 silent drivers from the index.

## Limits of this spike

- One process generates all load, on the same 4 vCPUs as the servers; a real deployment has more cores and network hops.
- PostGIS ran with default autovacuum settings. Aggressive per-table autovacuum would reduce bloat but cost more CPU; it doesn't change the ranking.
- Valkey ran without persistence or replication, matching the decision that live positions are rebuilt from new updates rather than recovered.

Raw output: [raw/](raw/) (`s1-*.txt`).
