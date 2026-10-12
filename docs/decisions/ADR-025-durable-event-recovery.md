# ADR-025: Persist event recovery work in PostgreSQL

- **Status:** Accepted
- **Date:** 2026-10-12
- **Related:** [LLD](../low-level-design.md) section 19.1; [ADR-008](ADR-008-outbox-and-events.md), [ADR-023](ADR-023-recurring-jobs.md)

## Context

Kafka consumers already commit database effects through the inbox before acknowledging offsets. Operations needs to inspect failed records, redrive individual failures and replay a time range after a consumer fix. The replay API returns `202`, and workers can restart while a request is running.

## Problem

Recovery must preserve malformed records, survive worker loss and outbox retention, and never erase deduplication state before the replacement effect can commit.

## Options considered

| Option | Benefits | Costs |
|---|---|---|
| Run a time-range query in the HTTP request | Few moving parts | Request timeouts; no durable progress; partial completion is ambiguous |
| Save a range and advance an outbox cursor | Small job rows | Retention and late commits can change membership while the cursor advances |
| Snapshot accepted events and process durable work rows | Stable membership and restartable progress | Temporarily duplicates event payloads |

## Decision

1. Mirror registered Kafka dead-letter topics before acknowledging their offsets. Store the key, value and ordered headers as bytes, including nulls and malformed values. Deduplicate by dead-letter topic, partition and offset. Database failures retry without discarding records.
2. Redrive the original consumer through the existing decoder and inbox. Lock the dead-letter row and commit its resolution with the consumer effects and operations audit. Invalid records are reported, not edited.
3. Accept a replay by snapshotting the target consumer's retained outbox events visible in one SQL statement, ordered by outbox ID, with `from <= occurred_at < to`. Store the event envelopes independently of the source outbox rows. Events committed after snapshot selection are not part of that request.
4. Process bounded batches under the replay row lock with `SKIP LOCKED`. Handler effects, inbox changes, deletion of completed snapshot rows and progress commit together. An ordinary replay retains inbox deduplication. Explicit `reprocess` removes only the selected consumer/event inbox entry in that same transaction.
5. A handler failure rolls back its batch and records `FAILED` only if the job has not advanced or changed retry generation since that attempt. Database failures leave the work pending. Operations can read status and committed progress by replay ID. An explicit, audited retry changes the generation and resumes the remaining snapshot, even if the source outbox has expired; it does not rerun committed batches.
6. Audit staff recovery commands and require their idempotency keys. Do not expose raw event payloads in listing or status responses. Keep unfinished work; completed and failed replay records use the platform's failed-delivery retention period, with their remaining snapshots deleted together.

## Trade-offs

- Snapshots consume database space until processed or retained failed work expires. They provide a bounded, inspectable request rather than an open-ended scan.
- A batch failure can rerun rolled-back handler work. Current handlers keep effects in the database; this is not an exactly-once guarantee for external side effects.
- Reprocessing is intentionally stronger than normal replay and can repeat business effects. It is staff-only and audited, not a default retry mechanism.
- Replay covers retained outbox data, not an archive of every event ever produced. It does not move live Kafka group offsets.

## Consequences

- Platform owns the mirror and replay tables; operations accesses them only through the public recovery API.
- The existing recurring-job runner schedules replay work on worker nodes. No new executor or scheduler service is introduced.
- Tests must cover byte preservation, database outages, duplicate offsets, redrive rollback, snapshot boundaries, restart, overlapping workers and failed reprocessing without losing inbox state.

## Revisit when

- Snapshot volume or recovery latency needs measured partitioning or object storage.
- Recovery needs transformations, targeted payload repair or external side effects; those require a separate reviewed workflow.