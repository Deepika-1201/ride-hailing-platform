# ADR-020: Valkey access: Lettuce, scripts by EVALSHA, sharded pub/sub

- **Status:** Accepted
- **Date:** 2026-10-02
- **Related:** [LLD](../low-level-design.md) §9, §14; [ADR-004](ADR-004-live-location-index.md), [ADR-006](ADR-006-realtime-transport.md), [ADR-015](ADR-015-identity.md); [spike S-1](../../spikes/results/s1-live-index.md)

## Context

- Valkey arrives in V2 (ADR-001). It carries:
  - the live index: one atomic script per location update, per candidate query, per status-mirror write and per sweep (ADR-004);
  - pushes on subject channels `drv:{id}` and `ride:{id}` (ADR-006);
  - WebSocket tickets (ADR-015) and rate-limit buckets.
- In AWS it runs in cluster mode (ElastiCache for Valkey); locally, one node. It has no persistence: a failover or restart can leave a node with an empty script cache.
- Rolling deploys run old and new application versions side by side for minutes, possibly with different versions of the same script.
- The application is Java on virtual threads (ADR-002). Spike S-1 ran the scripts from Go with `EVALSHA` and an `EVAL` fallback.
- In V1, in-memory implementations of the same ports stand in for Valkey, so V1 runs on PostgreSQL alone.

## Problem

1. Which client library?
2. How are server-side scripts deployed, invoked and versioned?
3. Which pub/sub flavour carries pushes in cluster mode?

## Options considered

### Client

| Option | Pros | Cons |
|---|---|---|
| **A. Lettuce, used directly** | Pure Java on Netty; cluster topology refresh; sharded pub/sub; Spring Boot's default driver, so versions are managed | Lower-level API than Spring Data's templates |
| B. Spring Data Redis templates on Lettuce | Convenient templates; script executor with `EVALSHA` fallback built in | Another abstraction over the commands we want to see; its listener container is built around classic pub/sub |
| C. Valkey GLIDE | The Valkey project's official client; designed with ElastiCache | Rust core loaded through JNI, so a native library per platform; a younger Java ecosystem |
| D. Jedis | Simple, blocking | A connection pool per node; weaker cluster pub/sub support |

### Scripts

| Option | Pros | Cons |
|---|---|---|
| **A. `EVALSHA`, falling back to `EVAL` on `NOSCRIPT`** | Content-addressed: two versions of a script coexist during a rolling deploy without coordination; an empty cache after failover costs one `EVAL` | Scripts aren't named on the server |
| B. Valkey Functions (`FUNCTION LOAD`, `FCALL`) | Named and versioned libraries, replicated to replicas | Must be loaded on every primary, again after a node is replaced, and versioned by library name so old and new code can coexist; the application needs reload logic anyway |
| C. Plain `EVAL` on every call | Nothing to manage | Sends the script body with each of the 12,500 updates/s |

### Pub/sub in cluster mode

| Option | Pros | Cons |
|---|---|---|
| **A. Sharded pub/sub (`SPUBLISH`, `SSUBSCRIBE`)** | A message stays on the shard that owns its channel's slot, so throughput grows with shards | Needs cluster-aware subscription handling in the client |
| B. Classic pub/sub (`PUBLISH`) | Works everywhere | In a cluster every message is broadcast to every node, so adding shards doesn't add pub/sub capacity |

## Decision

1. **Lettuce, used directly**, behind small ports owned by the modules that need them: `LiveIndex` (location), `PushBus` (realtime), `TicketStore` (identity), `RateLimiter` (platform). No Spring Data Redis. Lettuce 6.5 or later is required, because earlier versions didn't re-subscribe sharded channels after a reconnect.
2. **Scripts** are Lua files in `src/main/resources/valkey/`, loaded with `SCRIPT LOAD` at startup and called with `EVALSHA`. On `NOSCRIPT` the call is repeated once with `EVAL`, which also reloads the script. Every key a script touches is passed in `KEYS`, and all keys of one call share the `{city}` hash tag, so each call runs on one shard.
3. **Sharded pub/sub** in cluster mode; classic pub/sub against the single local node. Channel names are the same in both.
4. **Timeouts** are set per use: 50 ms for dispatch queries and mirror writes, 100 ms for location updates and pushes **(assumed)**. Topology refreshes on `MOVED`/`ASK` and every 30 s.
5. **Valkey 8** everywhere: one node locally and in Testcontainers; cluster mode with a replica per shard in AWS (ADR-018). Persistence stays off (ADR-004).

## Trade-offs

- The application owns a little plumbing that Spring Data would provide: script loading, the `NOSCRIPT` retry and the subscription registry.
- Scripts aren't named on the server, so operational inspection goes through logs and metrics, not `FUNCTION LIST`.
- Locally, classic pub/sub hides the sharded code path; a Testcontainers cluster test covers it (LLD §17).

## Consequences

- A rolling deploy that changes a script needs no coordination: old nodes call the old hash, new nodes the new one.
- After a Valkey failover, the first call of each script on the new primary costs one `EVAL`; nothing else needs reloading.
- Each port has an in-memory implementation for V1 and unit tests, and one contract test suite runs against both implementations.

## Revisit when

- Valkey Functions gain something scripts can't provide, for example per-function access control that operations needs.
- GLIDE becomes the common choice on ElastiCache and its Java client matures.

## Amendments

- **2026-10-07, phase 14** ([LLD §2.1](../low-level-design.md#21-allowed-dependencies), [§14.7](../low-level-design.md#147-phase-14-details)): `PushBus` is declared in `platform`, not in a realtime module. `dispatch` and `ride` publish pushes, and the new `realtime` module depends on both of them, so a port of its own would close a cycle. The port's two implementations follow the store: in memory, or Valkey pub/sub, sharded in a cluster.
