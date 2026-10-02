# ADR-004: Live driver positions in Valkey GEO sets, sharded by city

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [Requirements](../requirements.md) FR-L1–L4, FR-DS1, NFR-5–NFR-7; [ADR-001](ADR-001-architecture-style.md), [ADR-003](ADR-003-postgresql-postgis.md); [spike S-1](../../spikes/results/s1-live-index.md)

## Context

- Online drivers send 12,500 location updates/s at the cloud tier and 125,000/s designed-for. Each update is worthless 4 s later.
- Dispatch asks, about 300 times/s at the cloud tier: "the 20 nearest **available** drivers of this category within this radius, heard from in the last 30 s".
- Live positions don't need to survive a crash: they rebuild from new updates within one interval (NFR-6). Assignments do, and they live in PostgreSQL.
- Spike S-1 measured four designs at the laptop and cloud tiers.

## Problem

Where do live positions live, and how is the nearest-available-drivers query answered?

## Options considered

Figures are from S-1 at the cloud tier (50,000 drivers, 12,500 updates/s, 300 queries/s).

| Option | Pros | Cons |
|---|---|---|
| **A. Valkey GEO set per city and category, holding available drivers** | 0.26 server cores; 0.19 ms server time per query, p99 8.7 ms end to end; one round trip per update and per query | GEO members can't expire individually, so freshness needs a last-seen check and a sweeper; commands run on one thread per shard |
| B. Valkey set per H3 cell, positions in a hash | Cheapest updates: set changes only when a driver changes cell or availability | Every query reads each candidate's position (~376 hash reads), so 4× query latency and 1.7× CPU |
| C. PostGIS table with a GiST index | Same database as everything else | 1.46 cores; 6.6 dead row versions per live row within 80 s; p99 17–34 ms with tails near 0.8 s; 3.7 MB/s of WAL |
| D. PostGIS, unlogged table | WAL down 90% | Same CPU, twice the dead rows, query p99 109 ms: the cost is row churn, not WAL |
| E. Redis 8 Query Engine (one index filtering geo, tags and numbers) | Category, availability and freshness in one query | Not offered by ElastiCache, which runs Valkey or Redis OSS 7.x, so it means Redis Cloud or running Redis ourselves; not measured |
| F. In-memory index per city inside `dispatch` | Fastest; no network hop | Needs city ownership, rebuild on failover and routing of updates to the owner; premature before V4/V6 |

## Decision

1. **Live positions live in Valkey**, never in PostgreSQL. Valkey is the BSD-licensed fork of Redis with the same commands; ElastiCache offers it in AWS, priced below its Redis OSS engine.
2. **Keys per city**, all sharing the hash tag `{city}` so a city lives on one shard:
   - `{city}:drv:<id>`: hash with sequence number, time, position, accuracy, heading, speed, status and category;
   - `{city}:geo:<category>`: GEO set of **available** drivers only;
   - `{city}:seen`: sorted set of last-update times.
3. **One script per update**, atomic for that driver: ignore it if its sequence number is not newer than the stored one (out-of-order or duplicate), write the hash, add to or remove from the GEO set according to availability, record the time.
4. **One script per query**: `GEOSEARCH … BYRADIUS r ASC COUNT 2k`, keep members heard from in the last 30 s, return k with distances. The radius widens per FR-DS1.
5. **A sweeper per city** (in `dispatch`, every 5 s) removes drivers silent for 30 s from the GEO sets. The query's freshness check covers the time between sweeps.
6. **Availability in Valkey is a hint.** The reservation in PostgreSQL decides (ADR-001). A candidate whose reservation fails is skipped.
7. **No persistence** for the live index. In the cloud a replica gives fast failover; after a total loss the index refills from updates within one interval, inside NFR-7's 10 s.
8. **H3 cells are for zones** (surge, demand and supply counts, dispatch partitioning), not for nearest-driver search. PostGIS holds service areas, zones and trip routes (ADR-003).

## Trade-offs

- Availability exists twice: authoritatively in PostgreSQL and approximately in Valkey, updated after commit. In the gap, dispatch may pick a driver who was just reserved; the cost is one wasted candidate, never a double assignment.
- One thread per shard runs every command. `GEOSEARCH` cost grows with the drivers inside the search area (0.02 ms at laptop density, 0.19 ms at cloud density), so a city much denser than the cloud tier needs a smaller first radius or splitting into overlapping regions.
- Silent drivers need deliberate handling: the sweeper and the freshness check.

## Consequences

- Location ingestion writes each update to Valkey in one round trip (0.6 ms p50 in S-1), far inside NFR-5's 1 s budget.
- Dispatch reads only Valkey to find candidates, then reserves in PostgreSQL.
- At S-1's cost per operation, one shard per city carries the cloud tier at about a quarter of one core. Busier cities get their own shards through the `{city}` hash tag.
- Trip routes need their own store, decided in the HLD.

## Revisit when

- A city's shard approaches one core of command time.
- Dispatch moves to per-city owners with in-memory indexes (option F), for example for batch matching in V4.
- The managed offering gains per-member expiry or secondary geo indexes.
