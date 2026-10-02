# ADR-016: Trip routes in daily PostgreSQL partitions, archived to object storage at scale

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [Location system](../location-system.md) §8; requirements FR-L6, FR-A2, Q17, §7; [ADR-003](ADR-003-postgresql-postgis.md), [ADR-004](ADR-004-live-location-index.md)

## Context

- Every location update from assignment to drop-off is kept for 90 days, for receipts, disputes and safety (FR-L6, Q17). Operations reads must be audited (FR-A2).
- Volume: ~375 points per ride. About 7,500 points/s and ~30 GB/day as rows at the cloud tier; ~300 GB/day designed-for (requirements §7).
- Unlike live positions, trip points are append-only: written once, read rarely, deleted in bulk.
- Updates can arrive late or twice (offline replays).

## Problem

Where do trip routes live, and how are they written, deduplicated and expired?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. PostgreSQL table partitioned by day** | Append-only writes don't churn (unlike S-1's live table); dropping a partition expires a day for free; PostGIS available for queries; no new store | Large at the designed-for tier |
| B. One row per ride with a compressed polyline | Tiny storage | Updated many times per ride, or built only at the end (lost if the node crashes) |
| C. Object storage only (S3) | Cheapest at scale | Needs a buffer or stream in front; slower reads; no local equivalent before V3 |
| D. A time-series database | Built for this shape | One more store; not on RDS |

## Decision

1. **`location.trip_points`**, partitioned by receive day, unique per `(receive day, ride, sequence)`. Points keep device time, receive time, position, accuracy, speed, heading and quality flags.
2. **Writes:** `realtime` nodes buffer points and insert them in batches every 2 s or 500 points, ignoring duplicates. From V3 an archiver can rebuild routes from the location stream instead.
3. **Retention:** a daily job creates tomorrow's partition and drops partitions older than 90 days.
4. **Access:** a ride's rider and driver (receipt map) and operations; every operations read is written to the audit log.
5. **Designed-for tier:** after a ride ends, its points are compressed into one S3 object per ride; PostgreSQL keeps only the last 2 days of partitions.

## Trade-offs

- At the cloud tier, 90 days of routes as rows is ~2.7 TB. Fine for short load tests, not for a long-running deployment at that scale, hence step 5.
- A crashed node loses its unflushed buffer (≤ 2 s of points), which FR-L6 tolerates.

## Consequences

- Live positions and trip routes never share a table: one churns constantly, the other is append-only.
- Expiring old data is a metadata operation, with no vacuum debt.

## Revisit when

- Storage or write volume exceeds what one primary should carry; move to S3 archiving earlier.
- Analytics need routes beyond 90 days: keep anonymized, aggregated forms only.
