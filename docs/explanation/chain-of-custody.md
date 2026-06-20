# Chain of custody

This document explains how the trace ledger turns independent Cashu operations into a navigable
chain of custody, and the design choices behind it. For how to operate it see the
[runbook](../how-to/operate-trace-ledger.md); for endpoints see the
[API reference](../reference/trace-api.md).

## The graph

Every traced operation (mint, swap, send, receive, melt, quote, …) is published as a signed
kind-9079 Nostr event and ingested into the ledger. The events form a directed acyclic graph
whose **edges are derived from shared proofs**, not stored explicitly: when one event's output
proof becomes another event's input, there is a `spend` edge between them. Other edge kinds —
`quote` (a quote and its settlement), `possession` (a SEND/RECEIVE bundle), `transfer` (a
cross-mint move), `attempt` (a failed operation's referenced inputs), and `tombstone` (a pruned
hop) — capture the non-spend relationships.

The canonical identity of a proof is the full tuple `(mint_url, keyset_id, Y)`, never `Y` alone.
Two mints whose 8-byte keyset ids collide must never share an index row, so the mint URL is
always part of the key. `Y = hash_to_curve(secret)` is derived from the secret but does not
reveal it, which is why the read and walk APIs key on `Y` and the CLI derives it client-side.

## Why a sidecar index

The signed events live in nostrdb (the system of record), but high-cardinality lookups —
"every event that touched this `Y`", "all events for this mint since T", proof history — would be
slow there. A SQLite **sidecar** holds one projection row per event plus per-proof rows, keyed
for those queries. The walk derives edges lazily from the sidecar's `(mint_url, keyset_id, Y)`
index, so adding an event never rewrites a stored graph.

## Activity and pruning

Each event has a derived **activity** classification — `active` or `terminal` — maintained by the
activity cache from four triggers: terminal-kind operations, all-outputs-spent, a settled/expired
quote, and a voucher reaching a terminal status. Terminality is what makes pruning safe: a fully
terminal sub-DAG has already moved its value through and has little forensic value, so the
retention engine can prune it ahead of the age threshold.

Pruning never breaks traversability. It removes the bulky signed payload but keeps a **tombstone**
plus the proof join keys, so a walk across a pruned ancestor returns an opaque tombstone node and
continues past it, reporting `prunedCount`. The original operation id is never reused, so a
re-published pruned operation is rejected rather than silently re-created.

## Privacy posture

The ledger is operator-internal. Read responses are shaped to the caller's authority: a
summary-level caller never receives `secret`, `C`, `witness`, or `bolt11`, regardless of which
endpoint they hit. Events can be stored `FULL`, `HASHED` (secrets replaced by HMACs under a
deployment redaction key), or `MINIMAL` (secrets dropped). Issuer fields on not-yet-bound events
are governed by a pre-voucher exposure control, and back-filled issuer attribution is annotated so
its provenance is auditable. Publishing is only ever to private, authenticated relays.

## Idempotency

Delivery is at-least-once through a durable outbox keyed by operation id, and the ledger dedups by
deterministic event id. Backfill reuses a deterministic UUIDv5 operation id derived from stable
context, so replaying a log never creates duplicates. Together these make outage recovery and
historical backfill safe.
