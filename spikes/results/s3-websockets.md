# S-3: WebSocket connections on the JVM

**Question:** how much memory and CPU does one WebSocket connection cost in the `realtime` role, on Tomcat (Spring MVC, virtual threads) versus Netty (Spring WebFlux)? ([requirements §12](../../docs/requirements.md#12-open-questions-for-design-spikes)) The second half of S-3, how a message reaches the node holding a connection, is a design question answered in [ADR-006](../../docs/decisions/ADR-006-realtime-transport.md).

**Answer:** with default settings Tomcat reserves ~72 KB of heap per connection and ran out of a 1 GB heap between 10,000 and 20,000 connections. With 1 KB buffers it needs ~17 KB of heap (~21 KB of process memory) per connection; Netty needs ~9 KB (~10 KB) and ~40% less CPU. Both carry the cloud tier on a handful of nodes. Decision: [ADR-006](../../docs/decisions/ADR-006-realtime-transport.md).

## Setup

- One Spring Boot 4.1 app on Java 25 (1 GB heap, G1) in three setups:
  - **Tomcat default:** Spring MVC WebSocket on Tomcat, virtual threads enabled.
  - **Tomcat tuned:** the same, with 1 KB WebSocket message buffers and 1 KB socket read and write buffers. Driver messages are about 150 bytes.
  - **Netty:** Spring WebFlux on Reactor Netty.
- A Go client in the same VM opens connections at 1,000/s. Each connection sends a 150-byte location update every 4 s and receives a short acknowledgement.
- At each step the client holds the connections for 20 s, then asks the server for heap after a full GC and process RSS. CPU comes from the server container's cgroup during the hold.

## Results

| Connections (messages/s) | Tomcat default: heap / RSS / CPU | Tomcat tuned | Netty |
|---|---|---|---|
| 0 | 13 MB / 311 MB / – | 13 / 313 / – | 13 / 327 / – |
| 5,000 (1,250/s) | 373 / 671 / 0.20 | 98 / 465 / 0.16 | 57 / 418 / 0.13 |
| 10,000 (2,500/s) | 732 / 1,129 / 0.37 | 180 / 521 / 0.22 | 103 / 467 / 0.16 |
| 20,000 (5,000/s) | out of memory | 347 / 742 / 0.47 | 188 / 525 / 0.29 |

| Per connection | Tomcat default | Tomcat tuned | Netty |
|---|---|---|---|
| Heap after GC | ~72 KB | ~17 KB | ~9 KB |
| Process memory (at the largest step) | ~82 KB | ~21 KB | ~10 KB |
| CPU per message received and acknowledged (20,000 connections) | – | ~94 µs | ~58 µs |

Every setup kept every connection (0 failures) up to its last step; thread count stayed between 15 and 18.

## What the numbers say

1. **Defaults are a capacity trap.** Tomcat sizes several 8 KB buffers per connection by default. A realtime node sized by connection count alone would fall over at a fraction of its expected capacity, as the first run did. Buffers have to match the protocol.
2. **Tuned Tomcat is within 2× of Netty on memory and 1.6× on CPU.** At the cloud tier (~80,000 connections; ~12,500 location updates/s in and ~7,500 tracking messages/s out) that is four nodes of ~0.75 GB and well under one core each on Tomcat, or ~0.5 GB each on Netty. At the designed-for tier (~800,000 connections) Netty would save roughly 9 GB of memory and 40% of CPU across the fleet.
3. **Neither stack needs a thread per connection.** Virtual threads (Tomcat) and event loops (Netty) both kept the thread count flat.

## Limits of this spike

- Connections were nearly idle: one small message each way every 4 s. No TLS (the load balancer terminates it in the cloud) and no pub/sub routing in the loop.
- Client and server shared the same 4 vCPUs.

Raw output: [raw/](raw/) (`s3-*.txt`; `s3-servlet-first-run-oom.txt` is the run in which default Tomcat ran out of memory at the 20,000 step).
