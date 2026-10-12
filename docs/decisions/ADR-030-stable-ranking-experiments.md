# ADR-030: Stable percentage-based ranking experiments

- **Status:** Accepted
- **Date:** 2026-10-12
- **Related:** [ADR-011](ADR-011-dispatch-protocol.md), [ADR-027](ADR-027-weighted-dispatch-scoring.md)

## Context

Nearest, ETA and weighted rankers can already be selected per city/category. Phase 18 requires percentage splits and enough provenance to compare their outcomes without changing the conditional reservation protocol.

## Problem

Randomizing every attempt contaminates a ride's search with multiple policies. UUIDv7 prefixes are time ordered, and live configuration changes can alter retry behavior or make earlier decisions impossible to reproduce.

## Options considered

| Option | Benefits | Costs |
|---|---|---|
| Per-attempt random choice | Minimal code | Retries cross experiment groups |
| UUID prefix or numeric remainder | Cheap | Can correlate with creation time or ID generation |
| Versioned configuration, stable hash and search-policy snapshot | Repeatable cohorts and explainable retries | Stores a small snapshot per search task |

## Decision

1. Keep the category `ranker` as control. An optional `ranking_experiment` supplies ID, treatment ranker and integer percentage in 0..100. It uses the existing category version check and audit log.
2. Omission or null preserves existing configuration on update and means no experiment on insertion. Percentage zero disables treatment for new assignments. Existing searches finish with their captured policy; this is not an emergency kill switch.
3. Compute SHA-256 over `sha256-ride-v1`, experiment ID, city, category and canonical ride UUID, separated by newline, without a trailing newline. Interpret the first four digest bytes as an unsigned big-endian integer, modulo 10,000. Treatment applies when the bucket is below percentage times 100. Configuration version and percentage do not enter the hash, so percentage ramps retain nested cohorts.
4. The first committed attempt snapshots selected strategy, category version, weights, and experiment ID/control/treatment/percentage/bucket/algorithm on the locked search task. Later retries reuse it even after node restart or configuration changes, including index-error retries. Transaction rollback discards an uncommitted assignment. Offer TTL and search-radius settings continue using their existing path.
5. A search task recreated after acceptance starts a new search lifetime. It recomputes from then-current settings; an unchanged experiment ID and split retain the ride's bucket. Analyses must group by recorded assignment, not assume an entire ride spans one policy across separate searches.
6. Decision detail records the assignment, while existing strategy columns, offer events, ranker metrics and fallback metadata identify the strategy actually requested. Distance fallback is not silently relabeled as a control assignment.

## Trade-offs

- Percentages describe expected allocation, not exact quotas in small samples. The tiny modulo bias of a 32-bit hash is accepted for these experiments.
- This is reproducible allocation, not a tamper-resistant experimentation service or proof of improved outcomes.
- A new experiment ID deliberately changes cohort allocation. Deployment must retain ranker implementations while captured tasks still refer to them.

## Consequences

Test boundary allocation, stable buckets, category isolation, old-client compatibility, malformed input, database constraints, retry snapshots, configuration changes and decision provenance. Performance comparisons and adoption decisions remain separate phase exit gates.

## Revisit when

- Experiments need multiple treatments, per-driver cohorts, an emergency override, or assignments spanning multiple search lifetimes.