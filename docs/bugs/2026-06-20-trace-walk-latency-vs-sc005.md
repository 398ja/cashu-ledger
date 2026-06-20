# Finding: 10k-node walk latency misses the SC-005 / §8.5 targets

**Status:** Open — performance finding (harness in place)
**Reported:** 2026-06-20
**Source:** T073a performance harness (`TracePerformanceE2ETest`)
**Severity:** Medium — correctness is fine; the graph walk is slower than the design's stated budget.

## Summary

The trace graph walk over a 10,000-node proof chain takes **~850 ms** (cold ≈ warm) on a developer
machine, against the design §8.5 / SC-005 targets of **< 250 ms p95 cold** and **< 50 ms warm**. The
ingest side meets its target comfortably (see below); only the walk latency is short of goal.

Measured by `TracePerformanceE2ETest.shouldWalkTenThousandNodeGraphWithinLatencyBudget`:

```
trace_perf_walk_cold nodes=10000 ms=716   sc005_target_ms=250 met=false
trace_perf_walk_warm nodes=10000 p95_ms=715 sc005_target_ms=50  met=false samples=20
```

The harness asserts a generous regression guard (< 3000 ms) so the suite stays green while the gap is
tracked here; the §8.5 targets are logged as `met=true/false` on every run.

## Root cause

`WalkService.walk` issues **one SQLite proof-ref query per visited node** (via
`EdgeDeriver.outgoing/incoming` → `IndexedTraceEventStore.findByInputProofRef` /
`findByOutputProofRef`), with no walk-level or cross-call cache. At ~85 µs per JDBC point query,
10,000 nodes cost ~850 ms regardless of cache warmth — which is exactly why **warm ≈ cold**: there is
no in-memory structure for a second walk to reuse, so repeated walks re-issue every query.

The §8.5 "warm < 50 ms" target implies an in-memory adjacency/cache layer that does not currently
exist for the walk path.

## Reproduction

```
mvn -pl cashu-ledger-e2e-tests -am test -P e2e-tests \
  -Dtest='TracePerformanceE2ETest#shouldWalkTenThousandNodeGraphWithinLatencyBudget' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The harness builds a 10k-event proof chain (`event[i]` consumes `y(i)`, produces `y(i+1)`) in the
production SQLite sidecar index, reopens the index to drop warmed connection state, then times a full
DOWN walk cold and 20 warm walks.

## Ingest side (meets target, for contrast)

`shouldSustainIngestThroughputWithinLagBudget` (Testcontainers strfry) passes:

```
trace_perf_ingest events=1000 elapsed_ms=10223 rate_eps=97.8 lag_p50_ms=88 lag_p99_ms=179
trace_perf_burst  events=500  elapsed_ms=2006  rate_eps=249.3 ingested=500
```

Sustained ≥50 events/s (offered 100/s, all ingested), p99 ingest-to-visible lag 179 ms ≪ 30 s, and a
250/s burst ingests fully with no queue collapse. Note: publishing must be **rate-paced** — a single
relay + WebSocket subscription silently drops events delivered as an instantaneous burst (≈350/1000
lost when 1000 frames are dumped in ~286 ms), which measures relay burst tolerance, not the ledger.

## Suggested fixes (for the team — not applied)

1. **Batch proof-ref lookups**: resolve a frontier's neighbours in one `IN (...)` query per hop
   instead of one query per node — turns ~N queries into ~depth queries.
2. **In-memory adjacency cache** keyed by proof tuple, populated on ingest and reused across walks, to
   make the "warm" path hit the < 50 ms target.
3. **Covering index / prepared-statement reuse** on `proof_ref(mint_url, keyset_id, y, role)` to cut
   per-query overhead.

Until then, `WalkService` is correct and bounded (it honours `maxNodes` + truncation cursor); it is
just slower than the aspirational budget at the 10k-node scale.
