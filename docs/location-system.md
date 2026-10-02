# Location system

| | |
|---|---|
| Part of | [HLD](architecture.md) §7 |
| Status | Approved 2026-10-02 (refined by the [LLD](low-level-design.md#9-location-and-the-live-index)) |
| Decisions | [ADR-004](decisions/ADR-004-live-location-index.md) (live index), [ADR-006](decisions/ADR-006-realtime-transport.md) (transport), [ADR-016](decisions/ADR-016-trip-routes.md) (trip routes), [ADR-007](decisions/ADR-007-message-broker.md) (location stream) |
| Requirements | FR-L1–FR-L6, FR-A2, NFR-5, NFR-6, NFR-10, Q17 |
| Evidence | [Spike S-1](../spikes/results/s1-live-index.md), [spike S-3](../spikes/results/s3-websockets.md) |

## 1. Three kinds of location data

| Kind | What | Store | Kept | Consistency |
|---|---|---|---|---|
| Live position | The latest position of each online driver | Valkey | Until replaced (4 s) | Newest sequence wins; rebuildable |
| Trip route | Every update from assignment to drop-off | PostgreSQL, daily partitions | 90 days | Batched every 2 s; gaps tolerated |
| Location stream (V3) | Every accepted update | Kafka `location.updates` | 24 h | At least once; for analytics and counters |

Idle drivers leave no history: only their latest position exists (Q17).

## 2. Ingestion

A driver's app sends one message per update on its WebSocket ([ADR-006](decisions/ADR-006-realtime-transport.md)):

```json
{"type": "location", "seq": 1842, "lat": 12.97571, "lon": 77.60502, "accuracy_m": 8.5,
 "heading_deg": 91, "speed_mps": 7.2, "device_time": "2026-10-02T08:15:04.120Z"}
```

The driver's `realtime` node handles it in this order:

1. **Identity:** the driver comes from the connection's ticket, never from the message. Updates from a driver who isn't online are ignored.
2. **Rate:** at most 1 update per second per driver; extra ones are dropped and counted.
3. **Bounds:** valid numbers, inside the city's bounding box, non-negative accuracy.
4. **Quality:** accuracy and speed checks (§5) decide whether the position may be used for matching.
5. **Live index:** one Valkey script call ([§6](#6-live-index-and-status-mirror)). It drops updates that aren't newer and returns the driver's status and active ride from the status mirror.
6. **Tracking:** if the driver has an active ride, the node publishes the position on `ride:{rideId}` (§7) and buffers a trip point (§8).
7. **Stream (V3):** the node produces the update to `location.updates`, keyed by driver ID, asynchronously, with leader acknowledgement only, since losses are tolerated.

S-1 measured step 5 at 0.6 ms p50 at the cloud tier, so the whole path stays far inside NFR-5's 1 s.

## 3. Ordering, duplicates and late updates

- **Order matters per driver only.** Each update carries the app's sequence number, which increases with every update the app records, including those taken offline.
- The live-index script ignores any update whose sequence isn't greater than the stored one. That covers duplicates, retries and updates overtaken in flight (brief scenario 9).
- **Freshness uses the server's receive time**, never the device time, because device clocks can be minutes off (A-3). The device time is stored with trip points for reconstructing the route.
- **Offline replays:** after a tunnel or network loss, the app sends its buffered updates marked `replay: true`, oldest first, in batches of up to 100 over `POST /v1/drivers/me/location`, then resumes live updates on the socket. WebSocket frames are sized under 1 KB (spike S-3), which a 100-point batch would exceed.
  - Replayed points go into the trip route, deduplicated by ride and sequence.
  - Only a point newer than the stored one can move the live position.

## 4. Freshness and silent drivers

| Silence | Action | Who |
|---|---|---|
| 30 s | The driver is no longer returned by candidate searches: the query script filters on last-seen, and the sweeper removes them from the GEO set within 5 s | Query script, sweeper (`dispatch`, every 5 s per city) |
| 2 min while `ASSIGNED` | Ride reassigned and driver taken offline ([ride lifecycle T7](ride-lifecycle.md#3-transitions)) | Sweeper → ride module |
| 10 min while `AVAILABLE` | Driver taken offline (FR-L3) | Sweeper → dispatch module |
| Any silence while `ON_TRIP` | Nothing: the trip continues and the app's queued commands arrive later (FR-RD8) | — |

The 2-min and 10-min rules have a safety valve. They take no action while the live index is younger than the rule's threshold (after a loss of Valkey, nobody's silence means anything yet), or when a rule would act on more than 10% of a city's drivers at once (mass silence means the platform lost contact, not that drivers left). An alert fires instead ([LLD §8.9](low-level-design.md#89-sweeper)).

## 5. GPS quality

- **Accuracy** worse than 100 m: the update counts as a sign of life (last-seen) but doesn't move the live position used for matching. The trip route keeps it with its accuracy.
- **Implausible jump:** an implied speed above 150 km/h since the last good position marks the update `implausible` and it isn't used for matching. After 3 implausible updates in a row the new position is accepted as the new anchor, because the driver may genuinely have reappeared after a long tunnel. The event is logged for spoofing review.
- **Snapping to roads** isn't done. OSRM's matching service is an option once OSRM arrives in V4 ([ADR-013](decisions/ADR-013-routing-provider.md)).

Thresholds come from FR-L4 **(assumed)**.

## 6. Live index and status mirror

Keys (all with the `{city}` hash tag, so a city lives on one Valkey shard; [ADR-004](decisions/ADR-004-live-location-index.md)):

| Key | Content |
|---|---|
| `{city}:drv:<driverId>` | Hash: `seq`, `ts`, `lat`, `lon`, `accuracy`, `heading`, `speed`, `category`, `status`, `ride` |
| `{city}:geo:<category>` | GEO set of drivers whose `status` is `AVAILABLE` |
| `{city}:seen` | Sorted set: last receive time per driver |

**Update script** (called by `realtime`):
1. If `seq` isn't newer, stop.
2. Write the position fields, unless quality rules say otherwise.
3. Set last-seen.
4. If the hash's `status` is `AVAILABLE`, `GEOADD` the position; otherwise leave the GEO set alone.
5. Return `applied`, `status` and `ride`.

The status comes from the mirror, not from the app, so a driver can't make themselves available by sending a flag.

**Status mirror** (written by `dispatch` after each availability commit):

| Availability becomes | Valkey change |
|---|---|
| `AVAILABLE` (online, offer declined or expired, ride ended) | `status = AVAILABLE`, `ride` cleared, `GEOADD` at the last position |
| `OFFERED` | `status = OFFERED`, removed from the GEO set |
| `ASSIGNED`, `ON_TRIP` | `status` set, `ride = <rideId>`, removed from the GEO set |
| Offline | The hash becomes a tombstone that keeps the status version for 10 min; removed from the GEO sets and from last-seen |

The mirror is written after commit, so it can briefly lag or, if Valkey errors, be missed. A **reconciler** in `dispatch` compares each city's online drivers in PostgreSQL with the mirror every 30 s and repairs differences. A stale entry can only produce a candidate whose reservation then fails, because PostgreSQL decides (ADR-004).

**Query script** (called by `dispatch` and by `pricing` for the pickup ETA estimate):

```text
GEOSEARCH {city}:geo:<category> FROMLONLAT lon lat BYRADIUS r m ASC COUNT 2k WITHDIST
```

The script keeps only members seen within 30 s and returns up to k with distances. Spike S-1 measured 0.19 ms inside the server at cloud-tier density.

**Rebuild:** after a total loss of Valkey, the reconciler restores every online driver's status from PostgreSQL within 30 s, and positions come back with the next update (≤ 4 s). Matching therefore resumes within NFR-7's 10 s as soon as the status of most drivers is back. To shorten this, the reconciler runs at once when it detects an empty city.

## 7. Tracking for riders

- When a ride is assigned, the rider's `realtime` node subscribes to `ride:{rideId}`, after checking the rider owns the ride (FR-L5). It unsubscribes when the ride ends or the rider disconnects.
- The driver's node publishes each accepted position on that channel: `lat`, `lon`, `heading_deg`, `seq`, `eta_s`.
- The pickup ETA is recomputed through the routing provider at most every 15 s per ride **(assumed)** and attached to the next position. The rider sees a smooth countdown between recomputations.
- Positions are published only between assignment and drop-off, so nobody can track a driver outside a ride. Operations see the live map through their own authorized subscription.

## 8. Trip routes

Details in [ADR-016](decisions/ADR-016-trip-routes.md).

- **Table:** `location.trip_points`, partitioned by receive day. Columns: `ride_id`, `seq`, `device_time`, `received_at`, `lat`, `lon`, `accuracy_m`, `speed_mps`, `heading_deg`, `flags`. Unique per `(received_day, ride_id, seq)`.
- **Writes:** each `realtime` node buffers points and inserts them in one batch every 2 s or 500 points **(assumed)**, with duplicates ignored. At the cloud tier that is ~7,500 points/s in total, roughly 4 batches/s per node. The table is append-only, so these writes don't create the update churn that S-1 measured for live positions.
- **Loss:** a crashed node loses at most its unflushed buffer (≤ 2 s of points). From V3 the archiver can rebuild routes from the location stream instead.
- **Retention:** whole partitions are dropped after 90 days, which is cheap and leaves no bloat.
- **Reads:** the rider and driver of a ride can see its route on the receipt. Operations reads are audited (FR-A2).
- **Designed-for tier (~300 GB/day as rows):** after a ride ends, its points are compressed into one object in S3, and PostgreSQL keeps only the last 2 days.

## 9. Privacy

| Data | Who can see it |
|---|---|
| A driver's live position | The rider of the driver's active ride, during the ride; operations (live map) |
| A trip route | The ride's rider and driver; operations, with every read audited |
| Location stream (V3) | Internal consumers only; 24 h retention; analytics keep only counts per zone |
| Idle positions | Nobody: never stored beyond the latest position |

Positions appear in logs only at debug level and only together with a ride ID.

## 10. Capacity

| | Laptop | Cloud test | Designed for |
|---|---|---|---|
| Location updates/s | 500 | 12,500 | 125,000 |
| Valkey CPU for the live index (S-1) | ~0.03 cores | ~0.26 cores | ~2.6 cores across city shards |
| `realtime` nodes at ~20,000 connections each (S-3) | 1 (local process) | 4 | ~40, or fewer, larger Netty or Go gateway nodes (V5) |
| Trip points/s | ~300 | ~7,500 | ~75,000 (moved to object storage) |
| Tracking messages to riders/s | ~300 | ~7,500 | ~75,000 |

## 11. Failure modes

| Failure | Effect | Recovery |
|---|---|---|
| Valkey failover | Updates fail for a few seconds and are dropped; apps keep sending | Index refills on the next updates; mirror reconciled |
| A `realtime` node crashes | Its drivers reconnect elsewhere; ≤ 2 s of buffered trip points lost | Reconnect with backoff; resync |
| PostgreSQL unavailable | Trip points buffer up to 30 s per node, then the oldest are dropped; matching continues on Valkey | Buffers flush after failover |
| App offline | No updates; sweeper rules (§4) apply | Replays on reconnect (§3) |
| Device clock wrong | Nothing: server receive time decides freshness | — |
| Spoofed or glitching GPS | Flagged as implausible or poor accuracy; not used for matching | Spoofing review from the logged flags |

## 12. Metrics

| Metric | Meaning |
|---|---|
| `location_updates_total{result}` | Applied, stale, duplicate, poor accuracy, implausible, rate limited, offline |
| `location_pipeline_seconds` | Receive → indexed and published (NFR-5 SLI) |
| `location_device_lag_seconds` | Receive time minus device time: shows offline replays and clock skew |
| `live_drivers{city,category,status}` | From the mirror |
| `location_sweeper_removed_total` | Drivers removed for silence |
| `trip_points_buffered`, `trip_points_dropped_total` | Trip-route write health |
| `tracking_publish_seconds` | Publish → delivered to the rider's node |
