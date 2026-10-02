# ADR-006: WebSockets for live streams, HTTPS for commands, Valkey pub/sub to reach connections

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [Requirements](../requirements.md) FR-L1, FR-L5, FR-RD8, FR-DS3, NFR-5, NFR-12; [ADR-001](ADR-001-architecture-style.md), [ADR-002](ADR-002-java-spring-boot-jdbc.md), [ADR-004](ADR-004-live-location-index.md); [spike S-3](../../spikes/results/s3-websockets.md)

## Context

- Drivers send a location update every 4 s and must receive offers within their 15 s window. Riders watch their driver approach and see ride status change.
- About 80,000 open connections at the cloud tier and 800,000 designed-for. NFR-5: a location update reaches the rider's screen within 1 s (p95).
- Mobile connections drop often; clients reconnect, possibly to a different node.
- Spike S-3 measured memory and CPU per connection on Tomcat and Netty.

## Problem

1. Which transport between clients and servers?
2. Which server stack runs the `realtime` role?
3. How does a message (an offer, a status change, a position) reach the node holding the right connection?
4. How are authentication, heartbeats, reconnects, slow clients and ordering handled?

## Options considered

### Transport

| Option | Pros | Cons |
|---|---|---|
| **WebSocket** | Two-way on one connection; supported by browsers and the AWS load balancer | Long-lived connections need draining and reconnect handling |
| Server-sent events plus HTTPS uploads | Plain HTTP | One-way; every location update becomes an HTTP request (12,500/s) |
| gRPC streaming | Typed and compact | Browsers need a proxy, which complicates the demo web app |
| MQTT | Built for flaky mobile networks | Another broker to operate; browsers still need WebSocket underneath |
| Polling | Simplest | A 1 s freshness target means a request per rider per second |

### Server stack (S-3)

| Option | Pros | Cons |
|---|---|---|
| **Tomcat (Spring WebSocket) on virtual threads, 1 KB buffers** | Same programming model and JVM as the other roles, so one local process still runs everything (ADR-001); built-in per-session send limits; ~17 KB heap per connection | Defaults cost ~72 KB and ran out of a 1 GB heap before 20,000 connections; ~2× Netty's memory and ~1.6× its CPU |
| Netty (Spring WebFlux) | ~9 KB heap per connection, ~40% less CPU | Reactive code in one role; can't share one Spring application with Spring MVC, so the local all-in-one process would need a second embedded server |
| Go gateway | Lowest memory per connection | A second language and deployable; only worth it as a separate service (V5) |

### Reaching a connection

| Option | Pros | Cons |
|---|---|---|
| Broadcast to every node | No bookkeeping | Every node processes every message, so cost grows with node count |
| Registry of connection → node, plus a per-node inbox | Exact routing | The registry must follow every reconnect; two hops per message |
| **Subject channels on Valkey pub/sub** (`drv:{id}`, `ride:{id}`); a node subscribes to the subjects of the connections it holds | No registry; publishers don't know about nodes; sharded pub/sub scales with the Valkey cluster | At most once: a message is lost if its node or connection drops |
| A broker topic partitioned by user, connections pinned to partition owners | Durable delivery | Needs load-balancer routing by user; every rebalance moves connections |

## Decision

1. **WebSocket for streams.** Drivers send location updates and receive offers and status; riders receive their driver's position and ride status. **Commands are HTTPS calls** with idempotency keys (accept, decline, arrive, start, end, cancel), even though a socket is open, so every state change gets a response, a status code and a safe retry.
2. **The `realtime` role runs on Tomcat** with virtual threads, with 1 KB message and socket buffers sized to the protocol (driver messages are under 1 KB). Outbound messages go through a per-session decorator with a send-time limit and a buffer limit. For tracking only the latest position per driver is kept; if an offer or status message can't be sent within the limit, the connection is closed and the client resyncs.
3. **Routing through Valkey pub/sub subject channels** (sharded pub/sub in cluster mode): `drv:{id}` for offers and driver status, `ride:{id}` for ride status and the driver's position during a ride. A node subscribes when a connection it holds is authorized for the subject and unsubscribes on disconnect. A location update arrives at the driver's node, which writes the live index (ADR-004) and publishes the position on the driver's active ride channel.
4. **Push is an optimization; stored state is the truth.** Every message carries a version: ride version, offer ID with its expiry, or location sequence number. Clients drop anything older than what they have. On every connect and reconnect the client fetches current state over HTTPS (active ride, pending offer). A lost push therefore costs latency, never correctness: an offer whose push was lost is fetched on reconnect or simply times out.
5. **Connection lifecycle:**
   - The handshake is authenticated with a short-lived ticket obtained over HTTPS, because browsers can't set headers on WebSocket requests.
   - The server pings every 25 s, inside the load balancer's 60 s idle timeout.
   - Clients reconnect with exponential backoff and jitter.
   - On shutdown, a node stops accepting connections, asks its clients to reconnect spread over ~30 s, then closes (NFR-12).

## Trade-offs

- Tomcat costs about twice Netty's memory per connection. That's four nodes of ~0.75 GB at the cloud tier, which is fine; at the designed-for tier it's ~9 GB more across the fleet, which is what V5 revisits.
- Valkey pub/sub drops messages when a node fails; the resync rule makes that a latency cost.
- Clients speak two protocols, WebSocket and HTTPS.

## Consequences

- `realtime` nodes hold no state except their connections. Any node can serve any client, and the load balancer needs no stickiness.
- The NFR-5 path is: update → Valkey script (0.6 ms p50 in S-1) → pub/sub → the rider's node → the rider. That is a few milliseconds of server time plus mobile network time.
- Buffer sizes are part of the protocol definition, and capacity tests must check memory per connection, not just connection counts.

## Revisit when

- Memory per node limits connection density, for example when extracting the gateway in V5. Netty or a Go gateway is then the candidate.
- Lost pushes cause visible offer delays; a durable per-driver inbox would then be considered.

## Amendments

- **2026-10-02, low-level design** ([LLD §14](../low-level-design.md#14-realtime-v2), [review](../architecture-review.md) R-07, R-08):
  - Riders get a personal channel `rdr:{riderId}` for ride status; their node subscribes to `ride:{id}` on seeing an active ride there. `ride:{id}` now carries only the driver's position.
  - Offline replays of up to 100 updates go over HTTPS, because they don't fit the 1 KB frames chosen here.
