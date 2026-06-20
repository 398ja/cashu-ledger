# Finding: 10k-node walk latency vs SC-005 / §8.5 — RESOLVED

**Status:** Resolved — walk now meets the §8.5 budget; harness hard-enforces it.
**Reported:** 2026-06-20
**Resolved:** 2026-06-20
**Source:** T073a performance harness (`TracePerformanceE2ETest`)

## Summary

Initially the trace graph walk over a 10,000-node proof chain took **~850 ms** (cold ≈ warm) against
the design §8.5 / SC-005 targets of **< 250 ms p95 cold** and **< 50 ms warm**. After the fix below it
runs **~70 ms cold / ~20 ms warm median**, and `TracePerformanceE2ETest` hard-asserts the §8.5 numbers.

```
before:  trace_perf_walk_cold ms=850   warm median_ms=848
after:   trace_perf_walk_cold ms=77    warm median_ms=21 (p95 40)  (10k nodes, under full `verify` load)
```

Note: §8.5 qualifies the percentile on the *cold* figure ("< 250 ms p95 from cold cache, < 50 ms
warm"), so the harness asserts cold as a single first-touch sample and warm on the **median** (stable
under the concurrent load of a full `verify -P e2e-tests` run); warm p95 is logged for visibility.

## Root cause

`WalkService` visits every node once and, per node, asked the SQLite sidecar for the proof-ref edges
(`byProofRef` → one indexed query per node). At ~85 µs per JDBC point query, a 10k-node walk cost
~850 ms, and because nothing was cached in memory, a second walk re-issued every query — so **warm ≈
cold**.

## Fix

1. **In-memory proof-ref adjacency in `SqliteSidecarIndex`.** `byProofRef` now serves from a
   `Map<role|mintUrl|keysetId|y → events>` instead of querying SQLite per call. The map is built once
   at index open (`ensureAdjacencyLoaded`) and maintained incrementally on `index()`. This is safe
   because `proof_ref` rows are insert-only — prune retains them (§5.11), so the map never goes stale
   from deletes. A 10k-node walk becomes a sequence of hash-map lookups.
   *Trade-off:* the adjacency mirrors the proof-ref table in memory (~one entry per input/output
   proof) and is loaded at startup, adding a one-time open cost proportional to the proof-ref count.
2. **SQLite read pragmas** (WAL, `synchronous=NORMAL`, 64 MB cache, `temp_store=MEMORY`, 256 MB mmap)
   to speed the one-time bulk load and other reads.
3. **`WalkService` micro-trims**: skip the neighbour time-sort when a node has ≤ 1 neighbour (the
   common case), and `byProofRef` short-circuits the sole-referrer case — both avoid per-node stream
   and lookup overhead.

Correctness is unchanged: in-memory ordering reproduces the previous SQL `ORDER BY transition_at_ms
DESC, event_id DESC`; existing `WalkServiceTest`, `IndexedTraceEventStoreTest`, and the `TraceWalkIT`
integration walk all pass.

## Ingest side (also hard-enforced)

`shouldSustainIngestThroughputWithinLagBudget` (Testcontainers strfry):

```
trace_perf_ingest events=1000 elapsed_ms=10201 rate_eps=98.0 lag_p50_ms=55 lag_p99_ms=157
trace_perf_burst  events=500  elapsed_ms=2004  rate_eps=249.5 ingested=500
```

Sustains ≥50 events/s (offered 100/s, all 1000 ingested), p99 ingest-to-visible lag ≪ 30 s, and a
250/s burst ingests fully with no queue collapse. Publishing is **rate-paced**: a single relay +
WebSocket subscription silently drops events delivered as an instantaneous burst (≈350/1000 lost when
1000 frames are dumped in ~286 ms), which would measure relay burst tolerance, not the ledger.

## Reproduction

```
mvn -pl cashu-ledger-e2e-tests -am test -P e2e-tests \
  -Dtest='TracePerformanceE2ETest' -Dsurefire.failIfNoSpecifiedTests=false
```
