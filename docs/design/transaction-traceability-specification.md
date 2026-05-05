# Transaction Traceability Specification

> Diátaxis classification: **Reference + Explanation** (design specification).
> This document is the seed for a major feature and is intentionally thorough.

## 1. Problem Statement

The cashu-ledger project today records **voucher** lifecycle events (issue / claim / split / redeem / reclaim / revoke / expire) on Nostr. This is sufficient for high-level voucher accounting, but it does not capture the **underlying Cashu token-level transactions** that move ecash through a mint:

- A wallet calls `mint` against a Lightning quote and receives proofs.
- Proofs are `swap`ped for change, denomination management, or pre-send transformations.
- A `send` operation produces an outgoing token bundle (a set of proofs handed to a recipient).
- A `receive` operation imports a token bundle from another party (typically followed by a swap to take ownership).
- A `melt` operation burns proofs to settle a Lightning invoice.

Without per-operation records the operator cannot answer questions such as:

- "Which input proofs produced this output proof?" (forensics / debugging)
- "Show me the chain of custody for the 1024-sat proof currently sitting in voucher V." (audit)
- "When this melt failed, which proofs were consumed and which became change?" (incident response)
- "Visualise how value flowed in and out of mint M between time T1 and T2." (operational visibility)

The user has explicitly stated:

> "Everytime a token is minted, melted, swapped, etc I want to record this. The backend should fire an async message to the ledger with the necessary information and the ledger will record it. I want to be able to know which tokens were involved in the input, and which tokens resulted in the output. I want to be able to drill down and see the token content (and voucher details). This is what my ledger should ultimately become. I want to be able to visualise it, or part of it."

This document specifies the design for **transaction traceability** (also called "chain of custody"): a directed acyclic graph (DAG) of Cashu token-level operations, captured asynchronously from the backends that perform them, persisted by cashu-ledger, queryable via REST and CLI, and visualisable through the existing `cashu-ledger-web` module.

### Audience

- **Mint operators** running the imani.casa stack (primary; full visibility, including secrets).
- **Wallet / issuer backends** that publish traceability events (the producers).
- **Auditors and incident responders** who consume the read APIs and the visualisation.
- **Developers** debugging end-to-end voucher and token flows.

The feature is **not** intended to be a public service — see Section 7 (Security and Privacy).

## 2. Goals and Non-Goals

### 2.1 Goals

1. **Capture every Cashu lifecycle operation** — mint, swap, send, receive, melt, restore — as an immutable `TransactionEvent` with explicit input and output proof references.
2. **Asynchronous, non-blocking publishing** — backends fire-and-forget; user-facing operations never block on traceability persistence.
3. **At-least-once delivery with idempotency** — duplicate publishes are de-duplicated by a deterministic event id; missing events can be backfilled.
4. **DAG model** — every output proof is reachable from every input proof through directed edges; multiple traversal directions (upstream / downstream) are first-class.
5. **Drill-down to payload** — given a node, the operator can see the full proof contents (`amount`, `id`, `secret`, `C`, optional `witness`, optional DLEQ) and any voucher metadata that wraps the proof.
6. **Cross-link to the existing voucher ledger** — traceability nodes that participate in a voucher's lifecycle reference the existing voucher event id; a voucher event in turn references the transaction event that produced or consumed its proofs.
7. **Queryable REST API** consistent with the existing `cashu-ledger-client-api` style (NIP-98 auth, storage-first reads).
8. **CLI commands** consistent with `cashu-ledger` (`trace`, `chain`, `walk`, etc.).
9. **Web visualisation** — interactive graph rendering with drill-down panels, served by the `cashu-ledger-web` module.
10. **Cashu (NUT) compliance** — the data model must align with the proof structure defined in NUT-00 / NUT-01 / NUT-04 / NUT-05 / NUT-07 / NUT-09 / NUT-11 / NUT-12.
11. **Operate on the existing storage stack** — Nostr (via `nostr-java`) for transport, `nostrdb` for local storage, Spring Boot for the API.

### 2.2 Non-Goals

- **Anonymity-preserving public ledger.** This feature exposes proof secrets and is therefore an operator-internal facility; it is not a public chain.
- **Real-time consensus or Byzantine fault tolerance.** Traceability is an audit log, not a consensus system.
- **Replacing voucher events.** The existing `kind: 30078` voucher events remain authoritative for voucher state. Traceability events are additive and reference vouchers but do not duplicate their semantics.
- **Wallet-side enforcement.** Backends opt in to publishing; the spec does not require any wallet to publish traceability events to remain functional.
- **Cross-instance federation.** A single deployment owns its traceability ledger; merging traces across operators is out of scope (open question).
- **Editing or deleting events.** The ledger is append-only; corrections are issued as new events with `correction_of` references.
- **Capturing pre-mint Lightning routing data.** We record that a Lightning quote was paid, not the route.

## 3. User Stories

User stories follow the "As a / I want / so that" form with acceptance criteria.

### US-1 (Mint operator audit)

> **As a** mint operator,
> **I want** every mint, swap, and melt that my mint executes to appear as a transaction event in the ledger,
> **so that** I can audit value flow in and out of my mint without scraping logs.

**Acceptance criteria:**
- Every successful `POST /v1/mint`, `POST /v1/swap`, `POST /v1/melt` produces exactly one transaction event in the ledger within the configured latency budget (Section 4.4).
- A failed call (non-200 response, exception) does not produce a "success" event but may produce a `FAILED` event when the failure mode is observable post-hoc.
- Querying `/api/v1/trace?mintUrl=…&since=…&until=…` returns the events in chronological order.

### US-2 (Forensic drill-down)

> **As an** incident responder,
> **I want** to start from a single proof secret and walk the DAG upstream and downstream,
> **so that** I can identify which Lightning payment originally minted this value and where it ended up.

**Acceptance criteria:**
- The user supplies a proof secret to the CLI or web UI. The **client side** (CLI process or browser JS) computes `Y = hash_to_curve(secret)` locally and submits only `Y` (and optionally `keyset_id`) to the API. **The API never accepts a raw `secret` parameter.** This avoids transmitting spendable secrets across the wire and through audit logs.
- `GET /api/v1/trace/proofs/{Y}` returns the chronological list of transaction events involving that proof (Section 5.5), with role per event.
- `GET /api/v1/trace/proofs/{Y}/walk?direction=up&depth=10` returns up to 10 hops of upstream events terminating at root events (mint operations) or at the depth limit.
- Every node in the response includes the full proof payload (Section 5.2) when the requester is authorised.

### US-3 (Voucher to token cross-link)

> **As a** support engineer,
> **I want** to inspect a voucher and see the underlying mint/swap/melt operations that produced and consumed its proofs,
> **so that** I can explain to the user why their balance moved.

**Acceptance criteria:**
- The voucher inspect output (`GET /api/v1/client/vouchers/{id}` and `cashu-ledger inspect <id>`) gains a `transactionEvents` array listing related transaction event ids with operation kind and timestamp.
- Conversely, transaction events that wrap proofs belonging to a known voucher carry a `voucher_ref` tag pointing to the voucher d-tag.

### US-4 (Visualisation)

> **As a** mint operator,
> **I want** to render a portion of the DAG in a browser starting from a given anchor (proof, voucher, mint quote, or time range),
> **so that** I can visually communicate fund flows to a stakeholder.

**Acceptance criteria:**
- The web UI exposes a `Trace` page reachable from the existing dashboard.
- Entering a proof Y, voucher id, quote id, or event id renders a node-link diagram with up to N nodes (default 100, configurable up to 1000).
- Clicking a node opens a drill-down panel with full proof / voucher / quote payload.
- The graph is pannable and zoomable; the layout is deterministic for a given seed input.

### US-5 (Async non-blocking publish)

> **As a** wallet backend developer,
> **I want** to publish a traceability event without my user-facing operation waiting on the ledger,
> **so that** ledger outages or slowness never degrade wallet UX.

**Acceptance criteria:**
- Publishing API exposes a non-blocking method that returns immediately after enqueueing (in-process queue or transactional outbox).
- Failure to publish (network down, ledger unavailable) is logged and retried with exponential backoff according to the delivery contract in Section 4.4.
- Operation latency added by the publishing call (excluding async work) is < 5 ms p99 measured at the producer SDK boundary.

### US-6 (Idempotent backfill)

> **As a** mint operator,
> **I want** to replay missed events from a backend log without duplicating ledger entries,
> **so that** I can recover from outages cleanly.

**Acceptance criteria:**
- Republishing the same logical event (same operation context) yields the same event id.
- The ledger detects and discards duplicates, incrementing a counter visible via metrics.

### US-7 (Privacy posture configuration)

> **As a** mint operator,
> **I want** to configure how much sensitive data (secrets, blinded keys, witness payloads) is recorded,
> **so that** I can tune the trade-off between forensic power and exposure surface.

**Acceptance criteria:**
- A privacy mode setting (`FULL`, `HASHED`, `MINIMAL`) governs how proofs are serialised in events. See Section 7.2.
- Mode is enforced at the producer SDK; the ledger records what it receives but flags the mode in the event so consumers know what they have.

## 4. Functional Requirements

Numbered for traceability.

### 4.1 Event Capture

- **FR-1.** The ledger MUST recognise the following operation kinds: `MINT`, `SWAP`, `SEND`, `RECEIVE`, `MELT`, `MELT_REFUND`, `RESTORE`, `MELT_QUOTE_REQUESTED`, `MINT_QUOTE_REQUESTED`, `MELT_FAILED`, `MINT_FAILED`, `EVENT_PRUNED`. Additional kinds may be added under semver minor versions. `EVENT_PRUNED` is an internal audit kind emitted only by the ledger itself when a retention policy removes a payload (see NFR-5 and Section 5.9 — Pruning). `MELT_REFUND` is a compensating event that the producer emits when a NUT-08 / NUT-15 Lightning melt only partially settles (e.g., MPP under-delivery): the `MELT` records the proofs that actually burned, and a paired `MELT_REFUND` carries the unsettled remainder back to the user as fresh proofs (see §5.9).
- **FR-1a (Kind semantics).** Operation kinds are partitioned into three categories with distinct DAG semantics:
  - **Mint state-change kinds** (`MINT`, `SWAP`, `MELT`, `MELT_REFUND`, `RESTORE`) reflect operations the mint executed and that consumed/produced proofs at the mint. **Only these kinds produce proof-derived edges in the DAG.** Their input proofs are considered spent at the mint at the recorded `transition_at`. `MELT_REFUND` is unusual: it has empty inputs (the unsettled proofs were never burned) and ≥1 outputs (newly minted refund proofs); like `MINT` it is a graph root for its outputs.
  - **Possession-only kinds** (`SEND`, `RECEIVE`) describe off-mint custody changes (a wallet handed a token bundle to another wallet). They MUST be tagged `edge_role=possession` in the response and rendered as a distinct, non-spending edge in visualisation. They DO NOT consume proofs; the receiving party typically follows with a `SWAP` that does. SEND and RECEIVE that share the same proof set MUST link via a producer-supplied `bundle_id` tag (Section 5.3) rather than via proof edges, so that the DAG does not falsely double-consume the proofs.
  - **Quote / failure kinds** (`MINT_QUOTE_REQUESTED`, `MELT_QUOTE_REQUESTED`, `MINT_FAILED`, `MELT_FAILED`, `EVENT_PRUNED`) are reference / annotation nodes. `MINT_FAILED` and `MELT_FAILED` carry the inputs the operation attempted to consume but DO NOT produce a proof-derived edge marking those inputs as spent — instead they produce an `attempt` edge (auxiliary, non-spending) so that the DAG distinguishes "this proof was attempted in a failed melt" from "this proof was actually melted".
- **FR-2.** Each `TransactionEvent` MUST include: a stable event id (Section 4.5), operation kind, mint URL, ISO-8601 timestamp, an array of input `ProofRef`s (possibly empty for `MINT_QUOTE_REQUESTED`), an array of output `ProofRef`s (possibly empty for `MELT`), an actor pubkey, optional voucher reference, optional Lightning quote reference, optional fee, and a privacy mode marker.
- **FR-3.** The ledger MUST persist every received event exactly once (Section 4.5 idempotency).
- **FR-4.** Each `ProofRef` MUST carry: `amount`, `keyset_id`, the deterministic identifier `Y = hash_to_curve(secret)` (33-byte compressed point, hex-encoded), and the privacy-mode-dependent fields per Section 7.2. The canonical primary key for proof references and DAG joins is the tuple `(keyset_id, Y)` — never `Y` alone. Although `Y` is overwhelmingly likely to be unique on its own under current Cashu cryptography (`secp256k1` + `hash_to_curve`), future NUTs may introduce alternative curves or hashes and the tuple guards against that. Storage indexes and walk algorithms MUST therefore key on the tuple; clients MAY accept a bare `Y` in URL paths but the implementation looks up against the tuple internally and disambiguates collisions by returning all matches.
- **FR-5.** Proof-derived edges in the DAG are derived from matching `(mint_url, keyset_id, Y)` tuples between a producer event and a consumer event:
  - **Producer events** (those whose outputs can begin an edge): `MINT`, `SWAP`, **`RESTORE`**, **`MELT_REFUND`** — RESTORE and MELT_REFUND are treated as graph roots identical to MINT because they introduce proofs that have no upstream edge.
  - **Consumer events** (those whose inputs can terminate an edge): `SWAP`, `MELT`. RESTORE has empty inputs by definition (FR-5.9 / `R2`) and therefore is never a consumer; it appears in the DAG only as a root producer.
  - Failed-operation events (`MINT_FAILED`, `MELT_FAILED`) and possession events (`SEND`, `RECEIVE`) do NOT participate in proof-derived edge construction; they emit auxiliary edges per FR-1a.
  - Every producer-event output becomes a directed edge from that producer event to the next consumer event that accepts the same `(mint_url, keyset_id, Y)` as input. Edges are computed lazily by the read path; no separate edge events are required.
- **FR-5b (Double-spend visibility).** If two distinct mint-state-change events both consume the same `(keyset_id, Y)` as input, the ledger MUST surface both as edges from the producer event and tag the read response with `double_consume=true` plus the list of conflicting consumer event ids. This represents an observable misbehaviour at the mint or a bug; it is logged at WARN with a `double_consume_detected` event so an operator can investigate. The ledger does not attempt to adjudicate which consumer is correct — both edges remain visible in the DAG.
- **FR-5a (Pending vs. Final operations).** Quote-only operations (`MINT_QUOTE_REQUESTED`, `MELT_QUOTE_REQUESTED`) carry no proofs and exist as standalone DAG nodes. The corresponding terminal operations (`MINT`, `MELT`, `MINT_FAILED`, `MELT_FAILED`) MUST set the `quote_id` tag matching the originating quote event's `quote_id`, and the read path MUST surface this linkage as a dashed/auxiliary edge labelled `quote` (distinct from proof-derived edges). A quote event is never replaced or mutated; if a quote times out without resolution, an explicit `*_FAILED` event with `error_code=QUOTE_EXPIRED` finalises the chain.
- **FR-6.** The ledger MUST record the chronological order of events using both `transition_at` (producer-supplied wall-clock) and `created_at` (Nostr event time), with `transition_at` being the source of truth and `created_at` used as a tie-breaker.
- **FR-7.** The producer SDK MUST hook into the wallet/mint backend at the points where Cashu state actually changes (post-success), not at request entry. Failed operations may emit a `*_FAILED` event with the same input proofs and an empty output set plus an error code.

### 4.2 Async Message Bus

- **FR-8.** Producers MUST publish via an in-process transactional outbox or persistent queue so that publishing survives process restarts. (The simplest acceptable implementation is a SQLite-backed outbox; a durable JMS / Redis / Kafka backend is acceptable but not required.)
- **FR-9.** The outbox dispatcher MUST attempt delivery to all configured Nostr relays at-least-once with exponential backoff (initial 1s, factor 2, max 5 min, max 24h retention).
- **FR-10.** The dispatcher MUST require delivery confirmation to a **ledger-subscribed relay set** before marking the outbox row complete:
  - Producers are configured with the set of relays the ledger subscribes to (the "ledger relay set"). This set is published by the ledger via a discovery endpoint (`GET /api/v1/trace/relays`) and refreshed at producer startup or on demand.
  - The outbox row is marked complete only when at least one relay in the ledger relay set returns `OK: true`. A success on a relay outside the ledger relay set does not satisfy the contract and triggers continued retries.
  - If the producer cannot determine the ledger relay set (e.g., the discovery endpoint is unreachable at startup), it falls back to "configured ledger relays" listed statically in producer config.
  - This requirement closes the gap that mere relay acceptance does not imply ledger visibility.
- **FR-11.** The outbox MUST expose an operator-visible queue depth metric and a "stuck events" view (rows older than retention threshold).
- **FR-11a (Outbox overflow policy).** The outbox is bounded (default 100 000 rows). When at capacity, the configured overflow policy applies. The default and recommended policy is **`BLOCK_AND_ALERT`**: new `publish()` calls block up to a small bounded duration (default 250 ms) waiting for drain, and if drain does not occur, throw a `TraceabilityPublishException` to the caller — the caller decides whether to fail the user-facing operation or proceed without traceability. Alternative configurable policies:
  - `DROP_OLDEST_AND_ALERT` — drop the oldest row, emit a structured `TRACE_DROPPED` audit log line at ERROR with the dropped operation_id, and increment the `outbox_drops_total` metric. Use only when traceability is purely advisory.
  - `DROP_NEW_AND_ALERT` — refuse the new publish silently with a metric increment.
  - `FAIL_OPEN` — the publish call returns success without enqueueing; metric `outbox_silently_dropped_total` increments. Strongly discouraged.

  The default `BLOCK_AND_ALERT` makes traceability data loss visible to the producer and prevents silent dropping that would contradict the "capture every operation" goal. FAIL_OPEN exists for environments where availability strictly trumps audit completeness, and its use MUST be documented in the deployment runbook.

  **Operator guidance on policy selection.** The default is appropriate for typical mints (audit completeness > raw availability). High-volume producers (e.g., mints handling > 1 000 user-facing operations per second where any blocking on the request thread is unacceptable) SHOULD evaluate `DROP_NEW_AND_ALERT` or `FAIL_OPEN` — but the choice MUST be deliberate and documented because gaps in the DAG become possible. Producers running with a non-default policy emit `overflow_policy` on every event (FR-11b), so consumers can interpret missing data accordingly.
- **FR-11b (Lossy mode declaration).** A producer running with `DROP_*` or `FAIL_OPEN` overflow policy MUST tag every event with `["overflow_policy", "<policy_name>"]` so consumers know that gaps in the DAG may exist for that producer's pubkey. The `/stats` endpoint surfaces per-producer overflow policies.
- **FR-12.** The ledger ingest path (Nostr event subscription) MUST persist events into nostrdb via the existing `EventStore` abstraction, indexed appropriately (Section 5.4).

### 4.3 DAG Queries

- **FR-13.** The ledger MUST expose REST endpoints to:
  - look up a transaction event by id;
  - look up the event(s) referencing a given `Y` (input and/or output);
  - walk upstream / downstream from a starting node up to a depth and node-count limit;
  - list events for a mint URL and time range;
  - list events for an actor pubkey and time range;
  - list events that reference a given voucher id;
  - list events that reference a given issuer (merchant) id or issuer pubkey;
  - list events for a Lightning quote id;
  - filter any of the above lists by **event activity** (active / terminal / any) so callers can suppress events whose underlying tokens are no longer live (Section 5.4.1).
- **FR-13a (Event activity semantics).** Every transaction event has a derived `activity` classification: `ACTIVE` or `TERMINAL`. An event is `TERMINAL` if any of the following hold:
  - The event's referenced voucher (via `voucher_ref`) is in a terminal voucher status (`SPLIT`, `REDEEMED`, `RECLAIMED`, `REVOKED`, `EXPIRED` per `VoucherStatus.isTerminal()` in `cashu-ledger-core`).
  - The event is itself a terminal-kind operation: `MELT`, `MELT_FAILED`, `MINT_FAILED`, `EVENT_PRUNED`.
  - The event has at least one output proof AND every output proof has been consumed by a downstream mint-state-change event (`SWAP` or `MELT`). An event with zero outputs (e.g., `SEND`, `RECEIVE`, quote-only) is NOT terminalised by this rule alone — only the voucher-status and terminal-kind rules apply.

  Otherwise the event is `ACTIVITY=ACTIVE`. Quote-only events (`MINT_QUOTE_REQUESTED`, `MELT_QUOTE_REQUESTED`) are `ACTIVE` while pending and become `TERMINAL` when their settlement event arrives or they expire (the `/quotes/{id}/status` rule from §5.5 governs this transition). Activity is computed from already-stored data — no new Nostr tag is added — and is cached in the SQLite sidecar (Section 5.4.1) for filter-time performance.
- **FR-14.** Walk results MUST be deterministic for a given `(start, direction, depth, limit)` tuple; the traversal order is breadth-first with stable tie-breaking on `(transition_at, event_id)`.
- **FR-15.** Walk results MUST NOT exceed the configured `max-walk-nodes` (default 1000); if the limit is hit, the response MUST set `truncated=true` and include the cursor of the next unvisited frontier.
- **FR-16.** Drill-down endpoints MUST return the full payload (subject to authorisation and privacy mode) for a single node.
- **FR-17.** All read endpoints MUST honour the existing storage-first architecture: requests hit nostrdb; relay queries are limited to background sync.

### 4.4 Non-Functional Requirements

- **NFR-1 (Latency at producer):** Adding the publishing hook MUST contribute < 5 ms p99 to the user-facing operation **under nominal conditions** (outbox depth below the high-water mark; relays reachable). Under overflow conditions (outbox at capacity) the active overflow policy applies — `BLOCK_AND_ALERT` may add up to 250 ms before throwing, `DROP_*` and `FAIL_OPEN` retain the < 5 ms budget. The "fire-and-forget" goal (US-5) is preserved in steady state; degraded latency under overflow is the explicit, observable signal that the operator's traceability pipeline cannot keep up.
- **NFR-2 (End-to-end visibility):** New events are queryable via REST within 30 s p99 of the originating operation under nominal load (relay sync interval).
- **NFR-3 (Throughput):** Single ledger instance MUST sustain at least 50 events/s sustained ingest with 1-minute bursts of 500 events/s without queueing collapse.
- **NFR-4 (Storage growth):** Operators MUST be able to project storage from a per-event size budget. Target budget: ≤ 2 KB per event in `FULL` mode, ≤ 800 B in `HASHED` mode, ≤ 400 B in `MINIMAL` mode.
- **NFR-4a (Hard event-size limits, decoded sizes):** Producers MUST NOT submit and the ledger MUST reject events that exceed any of the following decoded sizes. Rejection codes are listed.
  - Total Nostr event JSON byte length: ≤ 64 KB (`EVENT_TOO_LARGE`).
  - Number of `input_y` tags: ≤ 64 (`TOO_MANY_INPUTS`).
  - Number of `output_y` tags: ≤ 64 (`TOO_MANY_OUTPUTS`).
  - Single `secret` byte length (UTF-8): ≤ 8 KB (`SECRET_TOO_LARGE`).
  - Single `witness` byte length (UTF-8): ≤ 16 KB (`WITNESS_TOO_LARGE`).
  - `bolt11` length: ≤ 4 KB (`BOLT11_TOO_LARGE`). (BIP-21 invoices well above this are atypical; the limit guards against pathological invoices.)
  - `error_message`: ≤ 1 KB (`ERROR_MESSAGE_TOO_LARGE`).
  Tests in Section 8 MUST cover oversized rejections. Operators with use-cases that breach these limits MUST contact the spec maintainers to negotiate a higher limit; raising a limit is a minor schema bump.
- **NFR-5 (Retention):** Default retention is unbounded (append-only); operators MAY configure a retention policy by `transition_at` age. Pruned events MUST emit an `EVENT_PRUNED` audit record.
- **NFR-6 (Idempotency):** Re-publishing an identical logical event MUST yield zero net effect on the stored DAG.
- **NFR-7 (Backfill):** A producer SHOULD be able to backfill events with `transition_at` in the past; the ledger accepts events older than `now - retention` only with an explicit `--allow-historical` ingestion flag.
- **NFR-8 (Observability):** The ledger MUST expose Prometheus metrics covering: events ingested by kind, events rejected by reason, queue depth, walk request duration, drill-down request duration, ingest-to-visible lag.
- **NFR-9 (Compatibility):** The Nostr event kind chosen for transaction events (Section 5.3) MUST NOT collide with kind `30078` used by vouchers; the producer schema MUST be versionable via a `schema_version` tag.
- **NFR-10 (Cashu compliance):** The proof structure persisted MUST be a strict subset/transformation of the canonical NUT-00 `Proof` JSON; round-tripping a NUT-00 proof through the ledger and back MUST yield byte-identical canonical fields (where `FULL` privacy mode is in effect).

### 4.5 Idempotency and Event ID

- **FR-18.** A transaction event's id (Nostr event id, computed as the SHA-256 over the canonicalised event per NIP-01) is implicitly deterministic given a deterministic `created_at`, `kind`, `pubkey`, `tags`, and `content`. To make events deterministic:
  - The `transition_at` tag is recorded with **millisecond precision** as a Unix-epoch integer (e.g., `1740000000123`). This avoids tied timestamps in high-throughput environments where multiple operations occur in the same second.
  - Nostr's `created_at` field (constrained to Unix seconds by NIP-01) MUST be set to `floor(transition_at_ms / 1000)`. The producer MUST NOT diverge from this rule.
  - Any event whose `created_at` does not equal `floor(transition_at_ms / 1000)` is rejected as `TIMESTAMP_MISMATCH`.
  - Content MUST be canonical JSON per RFC 8785 (Section 5.3 canonicalisation rules).
  Combined, these constraints make the Nostr event id deterministic for a given logical operation, while preserving sub-second ordering inside the ledger.
- **FR-19.** The producer MUST also include a `["traceability_op", "<operation_id>"]` tag whose value is a producer-assigned UUIDv7 over the logical operation. A second event with the same `traceability_op` but different content is rejected by the ledger as `OPERATION_CONFLICT`.
- **FR-19a (Operation-id persistence).** The `operation_id` MUST be persisted by the producer alongside the underlying domain operation (the database row representing the mint/swap/melt) **before** the user-facing operation acknowledges success, and reused on every retry or backfill of that same logical operation. UUIDv7 is generated once at the operation's first persistence; subsequent outbox redeliveries, restart-after-crash recoveries, and admin-driven backfills MUST resurface the same `operation_id`. This is what makes idempotency real — without persistent storage of the id, a UUIDv7 generated fresh on each retry would defeat dedup. The producer SDK provides a helper `OperationIdRegistry` that maps a domain `(operation_type, primary_key)` tuple to a stored UUIDv7 to make this easy.
- **FR-19b (Backfill operation ids).** When backfilling historical events from logs, the producer MUST derive the `operation_id` deterministically from stable operation context. The recommended derivation is `UUIDv5(namespace=cashu-ledger-trace, name=<mint_url>|<op>|<ISO8601-transition_at>|<canonical-sorted-input-ys>|<canonical-sorted-output-ys>)`. Producers MUST NOT use a fresh UUIDv7 for backfill; doing so will create duplicates if any portion of the backfilled range was previously published.
- **FR-20.** Independently of the producer-internal equality of `created_at` and `transition_at` (FR-18), the ledger MUST validate `created_at` against the **ledger's own wall-clock at ingest time** as follows:
  - If `created_at` is in the **future** by more than `future-skew-tolerance` (default 60 s), reject with `CLOCK_SKEW_FUTURE`. Future timestamps cannot be a legitimate retry from an outbox.
  - If `created_at` is in the **past** by more than `outbox-retention-tolerance` (default 24 h, equal to FR-9 outbox retention), reject with `CLOCK_SKEW_STALE` unless the admin `--allow-historical` flag is set on the ingest path. Past timestamps within the 24-hour window are accepted without warning because they are legitimate outbox retries.
  - Within `[now - 24h, now + 60s]`, the event is accepted regardless of skew. This window is intentionally asymmetric: legitimate retries are old, but legitimate producer clocks should never be ahead.

## 5. Data Models and API Contracts

### 5.1 Domain Entities (Java records)

**Module ownership (decided).** All schema DTOs (`OperationKind`, `ProofRef`, `TransactionEvent`, `PrivacyMode`, `LightningRef`, `DleqProof`) live in a single new module: **`cashu-ledger-trace-core`**. Both the ledger (`cashu-ledger-core`, `-web`, `-cli`) and the producer SDK (`cashu-ledger-trace-publisher`) depend on `cashu-ledger-trace-core`. There is no parallel DTO surface. This avoids the version drift Codex flagged, at the cost of forcing the producer SDK to take a transitive dependency on `cashu-ledger-trace-core`. The trade-off is acceptable because the trace-core module is intentionally tiny (records + enums + canonicaliser; no Spring, no Lombok, no Nostr) and has no transitive dependency footprint that would conflict with embedding in third-party backends.

The package is `xyz.tcheeric.cashu.ledger.trace.core`.

```java
public enum OperationKind {
    MINT_QUOTE_REQUESTED,
    MINT,
    SWAP,
    SEND,
    RECEIVE,
    MELT_QUOTE_REQUESTED,
    MELT,
    MELT_REFUND,                  // partial Lightning settlement compensation (§5.9)
    RESTORE,
    MINT_FAILED,
    MELT_FAILED,
    EVENT_PRUNED
}

public enum PrivacyMode { FULL, HASHED, MINIMAL }

public enum EventActivity { ACTIVE, TERMINAL }

public record ProofRef(
        long amount,                     // serialised as JSON "amount"; always present
        String keysetId,                 // serialised as JSON "id" (NUT-00 keyset identifier); always present
        String y,                        // serialised as JSON "y", 66-char lowercase hex of compressed secp256k1 point; always present
        Optional<String> secret,         // serialised as JSON "secret"; raw NUT-00 secret in FULL, HMAC-SHA-256 hex in HASHED, omitted in MINIMAL
        Optional<String> c,              // serialised as JSON "C" (capital C per NUT-00); raw in FULL, HMAC-SHA-256 hex in HASHED, omitted in MINIMAL
        Optional<String> witness,        // serialised as JSON "witness" (NUT-11/14); raw in FULL, HMAC-SHA-256 hex in HASHED, omitted in MINIMAL
        Optional<DleqProof> dleq         // serialised as JSON "dleq" (NUT-12); present only in FULL, omitted in HASHED and MINIMAL
) {}

public record DleqProof(String e, String s, Optional<String> r) {}

public record LightningRef(
        String quoteId,                  // dashed UUIDv7, producer-supplied
        String mintUrl,                  // duplicated for cross-mint disambiguation
        Optional<String> bolt11,         // raw in FULL; HMAC'd in HASHED; omitted in MINIMAL
        Optional<String> paymentHash,    // present in FULL and HASHED (never confidential); omitted in MINIMAL
        Optional<Long> amount,           // quote amount in the event's `unit` (already normalised per UN1); required by /quotes/{id}/status
        Optional<Instant> expiresAt,     // when the quote expires; required by /quotes/{id}/status
        OperationKind quoteOperation,    // MINT_QUOTE_REQUESTED or MELT_QUOTE_REQUESTED — clarifies which side of the quote this is
        boolean partial                  // true on MELT and MELT_REFUND when Lightning settled only partially (MPP under-delivery, NUT-08/15); §5.9 B3_PARTIAL_SETTLEMENT
) {}

public record TransactionEvent(
        String eventId,                   // Nostr event id
        String operationId,               // UUIDv7, producer-supplied
        OperationKind kind,
        String mintUrl,
        String unit,                      // e.g. "sat", "msat", "usd"
        Instant transitionAt,             // millisecond precision
        Instant createdAt,                // second precision (NIP-01)
        String producerPubkey,            // hex; system component that signed
        Optional<String> initiatorPubkey, // hex; end-user identity, optional
        List<ProofRef> inputs,
        List<ProofRef> outputs,
        Optional<LightningRef> lightning,
        Optional<String> voucherRef,      // d-tag of related voucher event, if any
        Optional<String> issuerId,        // merchant identifier copied from VoucherNode.issuerId at publish time; denormalised for filter performance (see Section 5.4)
        Optional<String> issuerPubkey,    // hex Schnorr pubkey of the merchant copied from VoucherNode.issuerPublicKey at publish time
        Optional<Long> feeAmount,         // mint fees, NUT-04/05
        Optional<String> errorCode,       // populated for *_FAILED kinds
        Optional<String> errorMessage,
        Optional<String> correctionOf,    // event id this corrects
        PrivacyMode privacyMode,
        int schemaVersion,
        NostrEventMetadata source
) {}
```

### 5.2 Privacy Modes (proof serialisation)

| Field            | FULL                    | HASHED                                              | MINIMAL              |
|------------------|-------------------------|-----------------------------------------------------|----------------------|
| `amount`         | yes                     | yes                                                 | yes                  |
| `keysetId`       | yes                     | yes                                                 | yes                  |
| `y`              | yes                     | yes                                                 | yes                  |
| `secret`         | yes (raw)               | hmac_sha256(redaction_key, secret) hex              | omitted              |
| `c`              | yes                     | hmac_sha256(redaction_key, c) hex                   | omitted              |
| `witness`        | yes                     | hmac_sha256(redaction_key, json(witness)) hex       | omitted              |
| `dleq`           | yes                     | omitted                                             | omitted              |
| `lightning.bolt11`| yes                    | hmac_sha256(redaction_key, bolt11) hex              | omitted              |
| `issuer_id`      | yes                     | yes (verbatim)                                      | yes (verbatim)       |
| `issuer_pubkey`  | yes                     | yes (verbatim)                                      | yes (verbatim)       |

`y` is always present because it is the join key for the DAG and is itself a non-reversible derivation (`hash_to_curve(secret)`).

`issuer_id` and `issuer_pubkey` are exempt from redaction across all privacy modes — the existing voucher event publishes both in plaintext on the same relay, so redacting them on the trace event would not increase privacy. Hashing them would also break the `/issuers/{issuerId}/events` filter UX, since callers do not generally hold the `redaction_key`. See Section 5.3.1 for the rationale and Section 7.2 for the privacy posture.

**Important caveat about `Y` and confirmation attacks.** `Y` is non-reversible (you cannot recover `secret` from `Y`), but `Y` is **deterministically computable from `secret`**. An adversary in possession of a candidate set of secrets (e.g., from a leaked wallet backup or a compromised mint database) can compute each candidate's `Y` and check whether it appears in the ledger — confirming membership of specific secrets. HASHED and MINIMAL modes do NOT prevent this confirmation attack because `Y` is always published. The only defences are:

1. Restricting ledger read access (NIP-98 + authority gating, Section 7.3) so an adversary cannot query the ledger.
2. Restricting relay access (NIP-42 / IP allow-list) so the adversary cannot subscribe to relay events.

In other words: the ledger is **operator-internal regardless of privacy mode**. HASHED/MINIMAL are about reducing the blast radius if the ledger leaks (the secrets, witnesses, and bolt11 strings stay redacted), not about making the ledger safe to publish openly. This is recorded explicitly in Section 7.2.

**Redaction key (HASHED mode).** Redaction MUST use **HMAC-SHA-256 with a deployment-scoped redaction key**, not bare SHA-256. Bare SHA-256 over a secret is reversible by an adversary who already holds a candidate set (e.g., a leaked wallet backup): they can hash each candidate and look it up in the ledger, defeating the purpose of redaction. HMAC with a confidential key (the `redaction_key`) closes that confirmation channel for anyone outside the operator. Requirements:

- The `redaction_key` is a 256-bit secret held by the producer SDK, distinct from the Nostr signing key and from any wallet keys.
- All producers within a single operator deployment MUST share the same `redaction_key` so that hashed values are comparable across producers; the key SHOULD be rotated on a schedule (default: yearly), and rotation MUST emit a key generation marker tag (`redaction_key_id`) on every event so that consumers know which key was used.
- Compromise of the `redaction_key` collapses HASHED mode back to bare-SHA-256 confirmability; this risk MUST be documented in the runbook and the key MUST be stored in a KMS or sealed secret, not in plaintext config.

**Redaction Key Registry.** To support authorised hashed-mode verification (e.g., an auditor wanting to confirm "is this leaked secret in the ledger?"), the ledger maintains an admin-only registry of past `redaction_key`s indexed by `redaction_key_id`:

- `GET /api/v1/trace/admin/redaction-keys` — admin-only; returns the list of `redaction_key_id`s currently registered (NOT the keys themselves).
- `POST /api/v1/trace/admin/redaction-keys` — admin-only; registers a new key for a given `redaction_key_id`. The key bytes are encrypted with the ledger's at-rest key (Spring `Cipher` or KMS) and never returned in plaintext via the API.
- `POST /api/v1/trace/admin/redaction-keys/{id}/verify` — admin-only; takes a candidate plaintext value (e.g., a leaked secret) and returns the list of event ids whose hashed values match. The candidate is HMAC'd server-side using the registered key and never logged. Rate-limited to prevent fishing attacks.

Sharing keys with auditors holding `trace:read:hashed` is performed out-of-band (the verify endpoint suffices for one-shot audits without requiring the auditor to hold the key directly). Sharing the raw key with a third party is discouraged; if necessary, it is the operator's responsibility to do so via a secure side channel and to rotate the key afterwards.

### 5.3 Nostr Event Schema

**Kind selection (decided).** Use **kind `9079`** (regular, non-replaceable, application-specific). This avoids the parameterised-replaceable semantics that NIP-01 attaches to the 30000–39999 range, which would allow a future event with the same `pubkey + kind + d` tuple to mask earlier events at NIP-01 compliant relays. Transaction traceability is append-only; there is no notion of "the latest version of operation X". Using `9079`:
  - eliminates relay-side replacement risk;
  - keeps the `d` tag available as a content-addressed primary key (still required for ledger-side dedup) without it carrying NIP-01 semantics;
  - aligns with the spirit of "regular events" per NIP-01 (one event per logical action, never replaced).

The earlier proposal of `30079` is rejected (Open Question 14 / 5 closed). Compatibility note: ledger ingest and producer SDK MUST validate `kind == 9079`; a future schema break may bump the kind.

**`d` tag** carries the `operation_id` and is formatted as a **UUIDv7 in canonical 36-character dashed form** (lowercase hex, hyphens at positions 8/13/18/23). This is the only valid format; producers MUST NOT submit dashless or upper-case variants. The previous spec text saying "UUIDv7 hex without dashes" is corrected here — examples in this document already use dashed form, and the consistent rule is "always dashed canonical form".

#### Tag Schema

| Tag                  | Cardinality | Description                                                                |
|----------------------|-------------|----------------------------------------------------------------------------|
| `d`                  | 1           | operation_id, dashed UUIDv7                                                |
| `op`                 | 1           | OperationKind value lowercased                                             |
| `mint_url`           | 1           | Mint base URL, normalised: lowercase scheme+host, no trailing slash        |
| `unit`               | 1           | Currency unit, lowercase ASCII. SHOULD be drawn from the registry: `sat`, `msat`, `usd`, `eur`, `gbp`, `chf`, `jpy`, plus ISO-4217 lowercase for other fiat. Producers MUST NOT use plural forms (`sats`) or mixed case. The ledger rejects unknown values at WARN level (does not refuse ingest) so future units can be introduced without coordinated upgrades, but standardised values are strongly preferred. |
| `transition_at`      | 1           | Unix milliseconds (integer)                                                |
| `producer_pubkey`    | 1           | hex pubkey (64 chars) of the publishing system component. MUST equal the event's `pubkey` field unless a NIP-26 delegation tag is present and references the producer pubkey. |
| `initiator_pubkey`   | 0..1        | hex pubkey (64 chars) of the end user / wallet identity that initiated the operation, when distinct from the producer (e.g., the wallet user's Nostr key). NOT required to sign the event; the producer attests on their behalf. Indexed by the SQLite sidecar so operators can search "events initiated by user X". |
| `schema_version`     | 1           | Integer, currently `1`                                                     |
| `privacy_mode`       | 1           | full / hashed / minimal                                                    |
| `input_y`            | 0..N        | Composite value `<keyset_id>:<y_hex>` — colon-separated, lowercase hex     |
| `output_y`           | 0..N        | Composite value `<keyset_id>:<y_hex>` — colon-separated, lowercase hex     |
| `quote_id`           | 0..1        | Composite `<mint_url>::<quote_id>` to avoid cross-mint collisions on otherwise non-globally-unique quote ids. The literal separator `::` (two colons) is reserved. |
| `payment_hash`       | 0..1        | hex                                                                        |
| `voucher_ref`        | 0..1        | d-tag of related voucher event                                             |
| `issuer_id`          | 0..1        | Merchant identifier copied from `VoucherNode.issuerId` at publish time. Denormalised so `/events?issuerId=…` is a single-index scan (Section 5.4) without joining through `voucher_ref`. Lowercase ASCII, length ≤ 128. MAY be present without `voucher_ref` for events that the producer associates with a merchant but that have not yet bound to a specific voucher (see Section 5.3.1 — Issuer denormalisation). |
| `issuer_pubkey`      | 0..1        | Hex Schnorr pubkey (64 chars, lowercase) of the merchant; copied from `VoucherNode.issuerPublicKey` at publish time. The merchant signed the voucher event with this key, so it is a stable join key across vouchers issued by the same merchant. |
| `bundle_id`          | 0..1        | Producer-supplied UUIDv7 linking SEND and matching RECEIVE events (FR-1a)  |
| `transfer_id`        | 0..1        | Producer-supplied UUIDv7 correlating the two halves of a cross-mint flow (e.g., a swap-out at mint A paired with a swap-in at mint B). Each mint emits its own event with the same `transfer_id`. NOT used for intra-mint operations — `bundle_id` covers SEND/RECEIVE within the same operator. See §5.3.2 for the cross-mint correlation rules. |
| `fee`                | 0..1        | Numeric, in `unit`. Reports the *actually-charged* fee for completed operations; for FAILED events, this tag is omitted (no fee was charged). |
| `error_code`         | 0..1        | Producer-defined error code                                                |
| `correction_of`      | 0..1        | Nostr event id of the event being corrected                                |
| `traceability_op`    | 1           | Same as `d` (kept for forward compatibility / explicit lookups)            |
| `redaction_key_id`   | 0..1        | Identifier of the HMAC redaction key used (HASHED mode only)               |
| `output_role`        | 0..N        | Per-output classification: `target`, `change`, or `fee_return`. Indexed positionally; if present, the count MUST match `output_y`. Used to distinguish change proofs from intended outputs in SWAP / MELT operations (NUT-08 overpaid fee returns appear as `fee_return`). |
| `overflow_policy`    | 0..1        | One of `block_and_alert`, `drop_oldest_and_alert`, `drop_new_and_alert`, `fail_open` (FR-11b) |

#### Canonicalisation Rules (deterministic event id)

The Nostr event id is `SHA-256` over the canonical JSON serialisation per NIP-01. Determinism therefore requires every input to that serialisation to be canonical. Producers MUST follow these rules:

1. **`created_at`**: integer Unix seconds; MUST equal `floor(transition_at_ms / 1000)` (FR-18). Note: NIP-01 limits `created_at` to seconds; the millisecond precision lives in the `transition_at` tag value.
1a. **`transition_at` tag**: serialised as a decimal-digit string (Nostr tag values are strings). Example: `["transition_at", "1740000000123"]`. No leading zeros, no thousands separator, no exponent.
2. **`pubkey`**: 64-char lowercase hex; no `0x` prefix.
3. **`kind`**: integer `9079`.
4. **Tag ordering** (outer array): tags MUST be emitted in this stable order:
   - `d`, `op`, `mint_url`, `unit`, `transition_at`, `producer_pubkey`, `initiator_pubkey`, `schema_version`, `privacy_mode`, `redaction_key_id`, `traceability_op`, `bundle_id`, `transfer_id`, `voucher_ref`, `issuer_id`, `issuer_pubkey`, `quote_id`, `payment_hash`, `fee`, `error_code`, `correction_of`, `overflow_policy`, then all `input_y` (in input-array index order), then all `output_y` (in output-array index order), then all `output_role` (in output-array index order, must align 1:1 with `output_y`).
5. **Inner-tag arrays** are emitted as `[name, value1, value2, ...]` with values in the order specified by their per-tag rule. Strings are exact (no whitespace trim) and lowercase where the tag definition says so.
6. **`content`** is canonical JSON of a top-level object. Determinism rules:
   - **Always-present keys**: `inputs` and `outputs`. They MUST appear even when empty (e.g., `MINT` has `"inputs": []`); a missing `inputs` or `outputs` key is rejected.
   - **Optional keys**: `lightning`, `error_message`. When the producer-side value is absent, the key MUST be **omitted entirely** from the canonical form. A `null` value is invalid (rejected). Producers MUST NOT use a sentinel value to indicate absence.
   - **Reserved key**: `extra`. In schema v1, `extra` MUST be omitted entirely. The ledger rejects events that include an `extra` key in v1 (`UNKNOWN_CONTENT_KEY`).
   - **Other unknown keys**: rejected at v1 (`UNKNOWN_CONTENT_KEY`). Schema-version 2 may relax this to forward-compatible ignoring.
   - **Sorting and encoding**: Object keys sorted lexicographically per RFC 8785 (JCS); arrays preserve element order; strings are UTF-8 with NIP-01-compatible escaping (no escape of printable ASCII other than `"` and `\`); numbers are integers without exponent (the spec defines no floats — fractional units are represented in their minor-unit integer form).
   - **Empty objects**: `{}` and never `null`. The top-level content for an event with no inputs/outputs/lightning/error_message is `{"inputs":[],"outputs":[]}`.
   - **Proof order**: the `inputs` and `outputs` arrays preserve the producer-supplied proof order; that order is part of the canonical event and the `output_role` tag positions are aligned to it.
7. **`tags` ordering rationale.** A test vector suite (see Section 8.1) supplies golden bytes for each operation kind so producers in any language can verify their canonicaliser.
8. The `traceability_op` tag value MUST equal the `d` tag value exactly (case-sensitive, dashed). The ledger rejects events where they differ.

If any canonicalisation rule is violated, the resulting event id will not match the expected deterministic value and the ledger will treat the event as a *new logical event*, defeating idempotency. The producer SDK is responsible for enforcing canonicalisation; the ledger does not "fix up" events.

#### Example Event (FULL mode, swap)

```json
{
  "kind": 9079,
  "pubkey": "<actor_pubkey_hex>",
  "created_at": 1740000000,
  "tags": [
    ["d", "0192f70a-7b3c-7c6e-8a40-1a2b3c4d5e6f"],
    ["op", "swap"],
    ["mint_url", "https://mint.imani.casa"],
    ["unit", "sat"],
    ["transition_at", "1740000000123"],
    ["producer_pubkey", "<actor_pubkey_hex>"],
    ["initiator_pubkey", "<wallet_user_pubkey_hex>"],
    ["schema_version", "1"],
    ["privacy_mode", "full"],
    ["traceability_op", "0192f70a-7b3c-7c6e-8a40-1a2b3c4d5e6f"],
    ["voucher_ref", "v-1766748473969"],
    ["issuer_id", "merchant-acme-store"],
    ["issuer_pubkey", "9b4d2e1c3a5f6789..."],
    ["fee", "2"],
    ["input_y", "00ad12ef:02a1b2c3..."],
    ["input_y", "00ad12ef:03d4e5f6..."],
    ["output_y", "00ad12ef:0211aa22..."],
    ["output_y", "00ad12ef:0233bb44..."],
    ["output_role", "target"],
    ["output_role", "change"]
  ],
  "content": "{\"inputs\":[{\"amount\":64,\"id\":\"00ad12ef\",\"secret\":\"...\",\"C\":\"...\"}, ...],\"outputs\":[ ... ]}",
  "sig": "<schnorr>"
}
```

The `content` JSON uses NUT-00 canonical proof field names (`id`, `C`, `secret`, `amount`, optionally `witness`, `dleq`) — see Section 5.10 for the full wire-format mapping.

#### 5.3.1 Issuer Denormalisation (rationale)

`issuer_id` and `issuer_pubkey` are the merchant identifiers already carried on the existing voucher events (see `cashu-ledger-core/.../model/VoucherNode.java`: `issuerId`, `issuerPublicKey`). They are denormalised onto traceability events for two reasons:

- **Filter performance.** A query like `/events?issuerId=…&since=…&until=…` resolves through a single composite SQLite index (Section 5.4) instead of joining trace events to voucher events at read time. The SQLite sidecar does not store voucher events, only trace events, so the join would otherwise force a trip to nostrdb per matched row.
- **Issuer-without-voucher cases.** Some operations precede or never bind to a voucher: `MINT_QUOTE_REQUESTED` and `MINT` execute before voucher issuance; bare wallet swaps unrelated to vouchers carry no voucher reference. A producer that nonetheless knows the issuer (e.g., a merchant-operated mint where every mint operation is associated with its own merchant) can populate `issuer_id` / `issuer_pubkey` directly. When the producer does not know the issuer at publish time, both tags are omitted; a later `correction_of` event MAY back-fill them.

**Producer responsibility.** When a producer publishes an event with both `voucher_ref` and `issuer_*` tags, the issuer values MUST agree with the referenced voucher. The ledger does not cross-check this at ingest (the voucher event may not yet have arrived), but a background reconciler (Section 6.4) flags inconsistencies in `/stats` as `issuer_mismatch` for operator review.

**Privacy.** Issuer fields are NEVER hashed or omitted, even in `HASHED` or `MINIMAL` privacy modes. The voucher event itself publishes the issuer in plaintext on a relay, so redacting the same value on the trace event would not increase privacy and would break filter UX. This is recorded as a Section 5.2 exception and reinforced in Section 7.2.

#### 5.3.2 Multi-Mint and Cross-Mint Flows

A single ledger deployment may serve multiple mints (each with its own `mint_url`). Multi-mint visualisation and cross-mint flow correlation are governed by these rules:

**Visualisation default — per-mint sub-graphs.** The `/visualisation` endpoint and the web UI render each mint's events as an independent sub-graph by default. Operators rarely want a tangled megagraph; the per-mint view keeps each mint's DAG visually coherent. The web UI exposes an **"All mints" overlay toggle** that merges the sub-graphs into a single view, with each mint coloured distinctly and `transfer_id` edges drawn between them. The overlay is opt-in to avoid surprising users with a dense graph on first render.

**Cross-mint flows — two-event model.** When value moves between two mints (e.g., a wallet melts at mint A and re-mints at mint B), each mint emits its own trace event. The two events are correlated by a shared `transfer_id` tag (UUIDv7, producer-supplied):

- The two events are **independent** — each mint records what its own mint observed (`MELT` at A, `MINT` at B). Neither event's `inputs` references the other's proofs (different keysets, different `Y` values).
- The `transfer_id` is computed by the wallet/orchestrator that initiates the transfer; both producers MUST use the same value. If the producers cannot agree on a `transfer_id` (e.g., uncoordinated wallets), the events are emitted without it and the cross-mint link is invisible — that is acceptable and not an error.
- The single-event `transfer_id` model is rejected because it would require one mint to know proofs that exist only on the other mint, violating the "one mint per event" invariant (`mint_url` consistency, `M1` in §5.9).

**Indexing.** The SQLite sidecar adds a `(tag_name='transfer_id', tag_value, transition_at)` index alongside the existing `bundle_id` index (§5.4). Lookups for the second leg of a transfer are O(log n) across the events index.

**API.** `GET /events?transferId=<uuid>` returns the (typically two) events sharing a transfer id. The `/visualisation` endpoint uses the index to draw `edge_role=transfer` edges between matched events when the "All mints" overlay is active.

**Edge role.** A new `edge_role` value `transfer` joins the existing `spend`, `quote`, `possession`, `attempt`, `tombstone` set. It is drawn between two mint-state-change events that share a `transfer_id` and whose `mint_url` values differ. Drawn dashed in the UI to signal that no proofs are shared across the edge.

**Failure modes.** A `transfer_id` present on only one event (the other never arrived, or never published one) is rendered as a dangling half-edge in the overlay view, with a "missing counterpart" annotation in the API response (`transferCounterpartMissing: true`). It is NOT a validation error — the spec accepts that cross-mint coordination is best-effort.

### 5.4 Storage Indexing (decision: SQLite sidecar)

**Indexing strategy (decided).** nostrdb's tag index is optimised for single-letter tags per NIP-01. The traceability schema uses multi-letter tags (`input_y`, `output_y`, `mint_url`, etc.) which nostrdb does not index efficiently for high-cardinality lookups. Rather than extend `nostrdb-jni` (a deep change with cross-platform release implications) or alias to single-letter tags (which would obscure the schema and conflict with NIP-01 conventions), the spec mandates a **SQLite sidecar index** alongside nostrdb. The decision and trade-offs:

- nostrdb remains the **system of record** for the raw events. Only nostrdb stores the signed event bytes; the SQLite sidecar holds derived index data.
- The SQLite sidecar holds one row per (event, tag) projection: `(event_id, tag_name, tag_value, position)`. Indexed by `(tag_name, tag_value)`, `(event_id)`, and `(tag_name, tag_value, transition_at)`.
- On startup, the ledger compares the latest `transition_at` indexed in SQLite with the latest `transition_at` in nostrdb; if they differ, it triggers a **rebuild**. Rebuild iterates the surviving nostrdb events AND merges them with the `tombstones` table (Section 5.11) so that proof-derived index rows for pruned events are restored from the tombstone payload. Without merging tombstones, a rebuild after a pruning cycle would lose the pruned hops' join keys and break DAG traversability. Rebuild progress is exposed at `/api/v1/trace/admin/index-status`.
- If the sidecar is missing or corrupted, the ledger refuses to serve queries (`503 INDEX_UNAVAILABLE`) until rebuild completes. Rebuild is online-safe (other queries return 503 with a `retry-after` header).
- The sidecar's storage budget is approximately 250 bytes per indexed row; a swap event has ~6 indexed tags so the sidecar adds ~1.5 KB per event on top of nostrdb's storage.

(Open Question 16 from Round 1 is closed in favour of this decision; Open Question 10 in the original draft is removed accordingly.)

The `TraceEventStore` interface lives in `cashu-ledger-trace-core` and is implemented by `NostrDbWithSqliteIndexTraceEventStore` in the ledger:

```java
public interface TraceEventStore {
    boolean store(GenericEvent event, String relayUrl);

    Optional<StoredEvent> findByEventId(String eventId);

    Optional<StoredEvent> findByOperationId(String operationId);

    List<StoredEvent> findByInputProofRef(String keysetId, String y, int limit);

    List<StoredEvent> findByOutputProofRef(String keysetId, String y, int limit);

    List<StoredEvent> findByMintUrl(String mintUrl, Instant since, Instant until, int limit);

    List<StoredEvent> findByProducer(String pubkey, Instant since, Instant until, int limit);

    List<StoredEvent> findByInitiator(String pubkey, Instant since, Instant until, int limit);

    List<StoredEvent> findByVoucherRef(String voucherId, int limit);

    List<StoredEvent> findByIssuerId(String issuerId, Instant since, Instant until, int limit);

    List<StoredEvent> findByIssuerPubkey(String issuerPubkey, Instant since, Instant until, int limit);

    List<StoredEvent> findByQuote(String mintUrl, String quoteId, int limit);

    List<StoredEvent> findByBundleId(String bundleId, int limit);

    /**
     * Returns the cached activity classification for the event (Section 5.4.1).
     * Falls back to recomputing from the DAG and voucher-status cache if the
     * cached row is stale (e.g., the voucher-state watcher has not caught up).
     */
    EventActivity getActivity(String eventId);

    /**
     * Filter variant of any list query. When activity is null, no filter is applied.
     */
    List<StoredEvent> findFiltered(TraceEventQuery query);

    IndexStatus getIndexStatus();
}
```

Required SQLite indexes:

- `(event_id)` PK
- `(tag_name='input_y', mint_url, tag_value)` covering `(mint_url, keyset_id, y)` composite — `mint_url` is part of the index key to provide absolute isolation between mints in case two mints' 8-byte keyset ids collide. Walk algorithms always filter by `mint_url` of the anchor event when traversing.
- `(tag_name='output_y', mint_url, tag_value)` covering `(mint_url, keyset_id, y)` composite (same rationale)
- `(quote_expires_at, mint_url)` — auxiliary column in the events index table, populated from `LightningRef.expiresAt` for `MINT_QUOTE_REQUESTED` and `MELT_QUOTE_REQUESTED`. Required so `/quotes/{id}/status` can scan only the open-quote subset and compute `expired` without parsing every event's content JSON.
- `(tag_name='mint_url', tag_value, transition_at)`
- `(tag_name='producer_pubkey', tag_value, transition_at)`
- `(tag_name='initiator_pubkey', tag_value, transition_at)`
- `(tag_name='voucher_ref', tag_value)`
- `(tag_name='issuer_id', tag_value, transition_at)` — supports `/events?issuerId=…&since=…&until=…` and the convenience endpoint `/issuers/{issuerId}/events`. Bounded `tag_value` length (≤ 128 ASCII) keeps the index compact.
- `(tag_name='issuer_pubkey', tag_value, transition_at)` — the cryptographic merchant identity; preferred over `issuer_id` for cross-merchant aggregation since `issuer_id` is operator-supplied and may collide across deployments.
- `(tag_name='quote_id', tag_value)` (composite `<mint_url>::<quote_id>`)
- `(tag_name='bundle_id', tag_value)`
- `(tag_name='transfer_id', tag_value, transition_at)` — supports `/events?transferId=…` lookups for the second leg of a cross-mint flow (§5.3.2). Bounded cardinality (one or two events per id) keeps the index compact.
- `(activity, transition_at)` — auxiliary column on the events index table populated per Section 5.4.1; supports `?activity=active|terminal` filtering on every listing endpoint as a single-index scan.

### 5.4.1 Activity Classification (cache and invalidation)

Activity is derived from three inputs that already exist in the system: the `op` tag (terminal-kind detection), the voucher-status feed (`voucher_ref` → terminal status), and the proof-spent state (output proofs that appear as inputs of a downstream mint-state-change event). Recomputing on every read would mean either a JOIN per row or a graph walk per row, both unacceptable for paginated listing.

The sidecar therefore caches activity as a column on the events index table:

- **`activity TEXT NOT NULL DEFAULT 'active'`** — value `active` or `terminal`.
- **`activity_reason TEXT`** — one of `terminal_kind`, `voucher_terminal`, `all_outputs_spent`, `quote_settled`, `quote_expired`, NULL when active. Returned to clients verbatim so operators can audit why an event was classified terminal.
- **`activity_changed_at INTEGER`** — Unix milliseconds of the transition; NULL while active. Useful for "tokens that became inactive in the last 24 h" queries.

**Invalidation triggers** — the activity cache is updated at exactly these moments:

1. **On event ingest.** A new mint-state-change event whose inputs reference a `(mint_url, keyset_id, Y)` previously emitted as an output of an existing event triggers a re-evaluation of the *upstream* event. If every output of the upstream is now spent, the upstream transitions to `TERMINAL` with reason `all_outputs_spent`. The update is a single SQLite `UPDATE` keyed on the upstream event id.
2. **On voucher state change.** A new voucher event (kind `30078`) whose `d` tag matches `voucher_ref` of any indexed trace event AND whose new status is terminal triggers a bulk `UPDATE` setting `activity=terminal`, `activity_reason=voucher_terminal` on every trace event with that `voucher_ref`. Voucher state changes are observed by the `VoucherStateWatcher` component (Section 6.5).
3. **On quote settlement / expiry.** A `MINT` / `MELT` / `*_FAILED` event arriving with a `quote_id` matching an open `MINT_QUOTE_REQUESTED` / `MELT_QUOTE_REQUESTED` updates the quote-only event's activity to `TERMINAL` with reason `quote_settled` (or `quote_expired` for expiry, evaluated by a periodic sweep that uses the `(quote_expires_at, mint_url)` index).
4. **On terminal-kind ingest.** Events of kinds `MELT`, `MELT_FAILED`, `MINT_FAILED`, `EVENT_PRUNED` are written with `activity='terminal'` and `activity_reason='terminal_kind'` directly at ingest. No invalidation is ever needed.

**Eventual consistency window.** The cache is updated within the sidecar reconciler cycle (default 30 s, NFR-2). A client that filters `?activity=active` may briefly see an event that has just become terminal until the cache catches up; clients that need stricter consistency MUST follow up with `GET /events/{eventId}` which recomputes activity on demand. The `/stats` endpoint exposes `activity_cache_lag_seconds` so operators can monitor.

**Rebuild.** Sidecar rebuild (Section 5.4) recomputes activity for every event by walking the DAG and consulting the voucher-status snapshot in `cashu-ledger-core`. For a 10 M-event sidecar the rebuild adds approximately 4 minutes to the existing rebuild time on commodity hardware.

### 5.5 REST API

Base path: `/api/v1/trace`. All endpoints require NIP-98 auth (reusing the filter from `cashu-ledger-client-api`).

| Method | Path                                          | Description                                                            |
|--------|-----------------------------------------------|------------------------------------------------------------------------|
| GET    | `/events/{eventId}`                           | Fetch a single event. Optional `?include=parsed\|raw\|both` (default `parsed`) controls whether the raw `cashuB` token bundle is also returned for `SEND`/`RECEIVE` events (see §5.10 Bundle Token Handling). |
| GET    | `/operations/{operationId}`                   | Fetch by producer operation id                                         |
| GET    | `/proofs/{y}`                                 | Chronological list (origin → terminal) of all events involving this Y, with role (`input` or `output`) per event. Supports query `keysetId` to disambiguate (FR-4); if omitted, returns matches across all keysets. |
| GET    | `/proofs/{y}/walk`                            | Walk DAG; query: `direction=up\|down\|both`, `depth`, `limit`, `cursor` |
| GET    | `/events`                                     | List with filters: `mintUrl`, `producerPubkey`, `initiatorPubkey`, `issuerId`, `issuerPubkey`, `op`, `since`, `until`, `voucherRef`, `quoteId`, `transferId`, `activity` (`active` / `terminal` / `any`, default `any`), `limit`, `cursor` |
| GET    | `/vouchers/{voucherId}/events`                | Convenience: events tagged with this voucher. Supports the same `activity` filter as `/events`. |
| GET    | `/issuers/{issuerId}/events`                  | Convenience: events tagged with this merchant id. Supports `since`, `until`, `op`, `activity`, `limit`, `cursor`. The `issuerId` path parameter is the operator-supplied string identifier; for cross-deployment merchant aggregation, prefer the `issuerPubkey` filter on `/events` (cryptographic identity, no collision risk). |
| GET    | `/quotes/{quoteId}/events`                    | Convenience: events tagged with this quote. The `quoteId` path parameter is the **raw producer-supplied id** (dashed UUIDv7); the caller MUST also supply `?mintUrl=<url>` because `quote_id` collisions across mints are possible. Internally the ledger looks up the composite tag value `<mint_url>::<quote_id>`. |
| GET    | `/quotes/{quoteId}/status`                    | Synthesised quote lifecycle: `pending`, `succeeded`, `failed`, `expired`. Computes from presence/absence of terminal events plus quote `expires_at`. Same disambiguation rule: `quoteId` is the raw id and `?mintUrl=<url>` is required. |
| GET    | `/visualisation`                              | Returns a graph payload suitable for the front-end (nodes + edges) given an anchor and bounds |
| GET    | `/stats`                                      | Counts by kind, queue depth, lag |
| GET    | `/relays`                                     | Returns the ledger relay set (FR-10) plus `supported_schema_versions` for SDK discovery (§5.12) |

**Pagination (decided).** All list endpoints (`/events`, `/proofs/{y}`, `/vouchers/{voucherId}/events`, `/issuers/{issuerId}/events`, `/quotes/{quoteId}/events`, walk endpoints) MUST use **cursor-based pagination**, not offset-based. The cursor is an opaque base64url-encoded JSON object containing `(transition_at, event_id)`; clients pass it back unchanged. The response includes `cursor` (next page) or `null` (end of stream). Offset-based pagination is forbidden because new events ingested during a paginated scan would cause the offset to skip or duplicate items. Sort order for paginated endpoints is `(transition_at DESC, event_id DESC)` by default with an optional `?order=asc` parameter.

**Response shape — `GET /proofs/{y}`** (chronological history of a proof):

```json
{
  "y": "0211aa...",
  "keysetId": "00ad12ef",
  "events": [
    {"eventId": "...", "kind": "MINT", "role": "output", "transitionAt": "2025-02-19T12:00:00.123Z"},
    {"eventId": "...", "kind": "SWAP", "role": "input", "transitionAt": "2025-02-19T12:01:30.456Z"}
  ],
  "originEventId": "...",
  "terminalEventId": "...",
  "currentlySpent": true
}
```

**Response shape — `GET /quotes/{quoteId}/status`**:

```json
{
  "quoteId": "0192f70a-7b3c-7c6e-8a40-1a2b3c4d5e6f",
  "mintUrl": "https://mint.imani.casa",
  "operation": "mint",
  "status": "pending",
  "requestedAt": "2025-02-19T12:00:00.000Z",
  "expiresAt": "2025-02-19T12:05:00.000Z",
  "settledEventId": null,
  "failureReason": null
}
```

The `status` is computed as: `succeeded` if a terminal `MINT` or `MELT` event references this quote; `failed` if a `MINT_FAILED` or `MELT_FAILED` event references this quote; `expired` if the quote's `expires_at` has passed without a terminal event; otherwise `pending`.

**Response shape — `GET /relays`**:

```json
{
  "relays": [
    {"url": "wss://relay.imani.casa", "private": true, "role": "primary"},
    {"url": "wss://backup.imani.casa", "private": true, "role": "fallback"}
  ],
  "supportedSchemaVersions": [1],
  "currentSchemaVersion": 1,
  "deprecatedSchemaVersions": []
}
```

`supportedSchemaVersions` is the inclusive band the ledger will accept on ingest (per §5.12: `[N-2, N-1, N]` once enough versions exist). `deprecatedSchemaVersions` is the subset that is still accepted but emits `TRACE_DEPRECATED_SCHEMA` (today: empty). `currentSchemaVersion` is `N` — the version the ledger emits in its own outputs (e.g., synthesised `EVENT_PRUNED` tombstones). The producer SDK refuses to start if its compile-time `schema_version` is not contained in `supportedSchemaVersions` or is greater than `currentSchemaVersion + 1`.

#### Response shape — single event

```json
{
  "eventId": "abc123...",
  "operationId": "0192f70a-7b3c-7c6e-8a40-1a2b3c4d5e6f",
  "kind": "SWAP",
  "mintUrl": "https://mint.imani.casa",
  "unit": "sat",
  "transitionAt": "2025-02-19T12:00:00.123Z",
  "producerPubkey": "a1b2c3...",
  "initiatorPubkey": "d4e5f6...",
  "inputs": [
    {"amount": 64, "keysetId": "00ad…", "y": "02a1b2…", "secret": "…", "c": "…"}
  ],
  "outputs": [
    {"amount": 32, "keysetId": "00ad…", "y": "0211aa…", "secret": "…", "c": "…"},
    {"amount": 32, "keysetId": "00ad…", "y": "0233bb…", "secret": "…", "c": "…"}
  ],
  "lightning": null,
  "voucherRef": "v-1766748473969",
  "issuerId": "merchant-acme-store",
  "issuerPubkey": "9b4d2e1c3a5f6789...",
  "activity": "active",
  "activityReason": null,
  "activityChangedAt": null,
  "feeAmount": 2,
  "privacyMode": "FULL",
  "schemaVersion": 1,
  "bundleTokenRaw": null,
  "neighbours": {
    "upstream": [{"eventId": "...", "kind": "MINT", "y": "02a1b2…"}],
    "downstream": [{"eventId": "...", "kind": "MELT", "y": "0211aa…"}]
  }
}
```

#### Response shape — walk

```json
{
  "anchor": {"type": "proof", "y": "02a1b2...", "keysetId": "00ad12ef"},
  "direction": "down",
  "depth": 3,
  "nodes": [
    { "eventId": "...", "kind": "MINT", "transitionAt": "...", "summary": {...} },
    { "type": "tombstone", "prunedEventId": "...", "op": "swap" }
  ],
  "edges": [
    {
      "fromEventId": "...",
      "toEventId": "...",
      "edgeRole": "spend",
      "keysetId": "00ad12ef",
      "y": "02a1b2...",
      "amount": 32,
      "outputRole": "target"
    },
    {
      "fromEventId": "...",
      "toEventId": "...",
      "edgeRole": "quote",
      "quoteId": "0192f70a-7b3c-7c6e-8a40-1a2b3c4d5e6f"
    },
    {
      "fromEventId": "...",
      "toEventId": "...",
      "edgeRole": "possession",
      "bundleId": "0192f80b-7b3c-7c6e-8a40-1a2b3c4d5e6f",
      "keysetId": "00ad12ef",
      "y": "02a1b2..."
    },
    {
      "fromEventId": "...",
      "toEventId": "...",
      "edgeRole": "attempt",
      "keysetId": "00ad12ef",
      "y": "02a1b2..."
    },
    {
      "fromEventId": "...",
      "toEventId": "...",
      "edgeRole": "spend",
      "keysetId": "00ad12ef",
      "y": "02a1b2...",
      "amount": 32,
      "doubleConsume": true,
      "conflictingConsumers": ["other-event-id-1", "other-event-id-2"]
    }
  ],
  "truncated": false,
  "cursor": null
}
```

**`edgeRole` values:**

| Role         | Meaning                                                                                  | Drawn between                                          |
|--------------|------------------------------------------------------------------------------------------|--------------------------------------------------------|
| `spend`      | A proof produced by the upstream event was consumed by the downstream event              | mint-state-change → mint-state-change                  |
| `quote`      | A quote-only event preceded a settlement event with matching `quote_id`                  | quote event → MINT/MELT/MINT_FAILED/MELT_FAILED        |
| `possession` | A SEND/RECEIVE bundle moved proofs between custody parties (no on-mint state change)     | SEND ↔ RECEIVE with matching `bundle_id`               |
| `transfer`   | Two mint-state-change events on different mints share a `transfer_id` (§5.3.2). Drawn dashed; no proofs cross the edge. | event(mint A) ↔ event(mint B) with matching `transfer_id` and distinct `mint_url` |
| `attempt`    | A failed mint operation referenced an input proof that it tried — but did not — consume  | MINT_FAILED/MELT_FAILED → input proof's prior origin   |
| `tombstone`  | The downstream node is a tombstone for a pruned event (Section 5.11)                     | any → tombstone                                        |

#### Response shape — visualisation

The visualisation endpoint trims node payloads to a `summary` object (id, kind, transitionAt, mintUrl, totalIn, totalOut) and excludes proof secrets regardless of privacy mode, so that the front-end can render without holding sensitive data in browser memory. Drill-down is performed by issuing a follow-up GET to `/events/{eventId}` from the client.

### 5.6 CLI Commands

In `cashu-ledger-cli`, new top-level command `trace` with subcommands:

```
cashu-ledger trace event <event-id> [--raw]
cashu-ledger trace operation <operation-id>
cashu-ledger trace proof <y> [--direction up|down|both] [--depth N]
cashu-ledger trace events --mint-url <url> [--since <iso>] [--until <iso>] [--op <kind>] [--activity active|terminal|any]
cashu-ledger trace voucher <voucher-id> [--activity active|terminal|any]
cashu-ledger trace issuer <issuer-id> [--by-pubkey] [--since <iso>] [--until <iso>] [--op <kind>] [--activity active|terminal|any]
cashu-ledger trace quote <quote-id>
cashu-ledger trace stats
cashu-ledger trace export <anchor-spec> --format json|csv|graphml > out
cashu-ledger trace export <anchor-spec> --sanitise --output <dir>
```

Output formats follow the existing `--output text|json|tree` convention; `tree` renders an ASCII DAG. The `--raw` flag on `trace event` prints the raw `cashuB` bundle token alongside the parsed view for `SEND`/`RECEIVE` events; it requires `trace:read:full` authority and `FULL` privacy mode (rejected with `TRACE_FORBIDDEN` otherwise).

### 5.7 Producer SDK (separate artefact)

A small artefact named `cashu-ledger-trace-publisher` (Maven module to be added under `cashu-ledger`) provides:

```java
public interface TraceabilityPublisher {
    void publish(TransactionEvent event);   // non-blocking; enqueue + return
    PublisherHealth health();
}
```

- Uses `nostr-java` to build, sign, and submit events.
- Backed by an outbox abstraction: `OutboxStore` with `SqliteOutboxStore` (default) and `InMemoryOutboxStore` (for tests).
- Configurable signer: provide a Nostr secp256k1 Schnorr private key (32 bytes), or delegate via NIP-26 to another key. Nostr does not use Ed25519 — earlier draft text was incorrect and is corrected here.
- Provides Spring Boot auto-configuration for backends that already use Spring.
- Emits OpenTelemetry spans linking the user-facing operation to the publish call.

The SDK depends on `cashu-ledger-trace-core` (Section 5.1) for all DTOs; it does NOT depend on `cashu-ledger-core` (which carries voucher-related types, Spring, nostrdb-jni, etc.). This keeps the SDK footprint minimal while ensuring there is exactly one source of truth for the schema records — no parallel mirrored DTOs.

**Voucher / trace event publish ordering.** When the same operation produces both a voucher event (`kind 30078`) and a trace event (`kind 9079`) — typically a SEND that bundles proofs into a voucher — the SDK MUST publish the voucher event first, wait for at least one ledger-subscribed relay to acknowledge per FR-10, and only then publish the trace event with a populated `voucher_ref`. If the voucher publish fails or times out (per FR-11a outbox retention), the SDK publishes the trace event without `voucher_ref` and queues the voucher for background retry; consumers are expected to tolerate trace events whose `voucher_ref` is absent. The reverse is also tolerable: voucher events without trace events occur in pre-SDK deployments and remain readable through the existing voucher API. The dangerous case the ordering avoids is a trace event whose `voucher_ref` points at a voucher that never arrives — possible if the publishes were reversed and the voucher publish then failed. (Closes Open Question 4.)

### 5.8 Web Visualisation

The `cashu-ledger-web` module gains a `/trace` page:

**UI layout:**

- Top: anchor input — type selector (proof / voucher / issuer / quote / event id / time range) + value input + "Render" button. The "issuer" anchor accepts either an `issuer_id` string or an `issuer_pubkey` hex (auto-detected by length and charset); selecting an issuer renders all events tagged with that merchant within the active time range, time-ordered.
- Left: filter rail — privacy mode, kind multi-select, mint URL filter, **issuer filter** (text input matching `issuer_id` exactly or `issuer_pubkey` prefix), **activity toggle** (`Active only` / `Terminal only` / `All`, default `All`), **multi-mint view selector** (`Per-mint` (default) / `All mints overlay`, §5.3.2 — overlay merges sub-graphs and renders cross-mint `transfer` edges dashed), depth slider (1–10), max nodes slider (50–1000). Terminal nodes when shown are rendered with a desaturated palette and a small badge indicating the `activityReason` (`voucher_terminal`, `all_outputs_spent`, etc.). Each mint is assigned a stable colour from a deterministic palette derived from the `mint_url` hash so overlays remain visually consistent across renders.
- Centre: graph canvas. Renderer: Cytoscape.js or D3-force. Default layout: dagre (directed acyclic). Node shape encodes kind (circle = mint, square = swap, diamond = melt, etc.). Edge labels show amount.
- Right: drill-down panel. Selecting a node populates with full event payload. Selecting an edge shows the proof Y plus its full payload. Includes a "Copy operation id" and "Open neighbour" affordance.
- Bottom: status bar showing `nodes shown / nodes available`, `truncated` flag, ingest lag.

**Interactivity:**

- Click a node → populate drill-down panel, dim non-neighbours.
- Double-click a node → expand its neighbours (call `/proofs/{y}/walk?depth=1`).
- Drag to pan, wheel to zoom.
- "Anchor here" button on the drill-down panel re-roots the view at the selected node.

**Performance budget:** 60 fps for ≤ 200 nodes; degrade gracefully (no animation) for 200–1000.

### 5.9 Per-Operation Validation Invariants

Producers MUST satisfy these invariants before publishing; the ledger MUST verify them at ingest and reject violators with `INVALID_OPERATION` and the specific failure code in parentheses.

| Operation              | Inputs       | Outputs       | Lightning              | Fee                     | Other invariants                                                                 |
|------------------------|--------------|---------------|------------------------|-------------------------|----------------------------------------------------------------------------------|
| `MINT_QUOTE_REQUESTED` | empty (`E1`) | empty (`E2`)  | `quote_id` required    | omitted                 | `unit` matches the quote's unit (`U1`)                                           |
| `MINT`                 | empty (`E1`) | ≥ 1 (`O1`)    | `quote_id` required    | optional, ≥ 0           | `sum(outputs.amount) == quote.amount_in_event_unit` after unit normalisation (`B0`); all outputs share `unit` and `keyset_id` (`K1`); `unit` matches the quote's unit (`U1`) |
| `SWAP`                 | ≥ 1 (`I1`)   | ≥ 1 (`O1`)    | omitted                | optional, ≥ 0           | sum(inputs.amount) == sum(outputs.amount) + fee (`B1`); all share `unit` (`U2`) |
| `SEND`                 | ≥ 1 (`I1`)   | empty (`E2`)  | omitted                | omitted                 | `bundle_id` required (`L1`); if `bundleToken` is present, parsing it MUST yield exactly the `inputs` proof set (`B1_BUNDLE_MISMATCH`) |
| `RECEIVE`              | ≥ 1 (`I1`)   | empty (`E2`)  | omitted                | omitted                 | `bundle_id` required (`L2`). The matching `SEND` is NOT required to have arrived at ingest — out-of-order arrival is normal across federated relays. The ledger accepts the RECEIVE, marks it `unmatched_send=true` in API responses, and reconciles automatically when the SEND arrives (or vice-versa). A RECEIVE that remains unmatched beyond `bundle-reconciliation-window` (default 7 days) is flagged in `/stats` as `dangling_receive` for operator review. (`L3`). If `bundleToken` is present, parsing it MUST yield exactly the `inputs` proof set (`B1_BUNDLE_MISMATCH`). |
| `MELT_QUOTE_REQUESTED` | empty (`E1`) | empty (`E2`)  | `quote_id` required    | omitted                 | `payment_hash` required if quote returned one (`Q1`)                            |
| `MELT`                 | ≥ 1 (`I1`)   | 0..N          | `quote_id` required    | required, ≥ 0 (`F1`)    | `sum(inputs.amount) == quote.amount_in_event_unit + fee + sum(outputs.amount)` after unit normalisation (`B2`); outputs are `change`/`fee_return` only (`R1`) |
| `MELT_FAILED`          | ≥ 1 (`I1`)   | empty (`E2`)  | `quote_id` required    | omitted                 | `error_code` required (`X1`); does NOT consume inputs in the DAG (FR-1a)         |
| `MELT_REFUND`          | empty (`E1`) | ≥ 1 (`O1`)   | `quote_id` required    | required, `partial=true`| Compensates the unsettled portion of a partial Lightning melt. MUST reference the same `quote_id` as the paired `MELT`; ledger asserts `MELT.feeAmount + MELT.outputs[fee_return].amount + MELT.inputs.amount_consumed_in_settlement + MELT_REFUND.outputs.sum == MELT.inputs.sum` after unit normalisation (`B3_PARTIAL_SETTLEMENT`). The `MELT_REFUND` MUST arrive within `partial-settlement-window` (default 60s) of the `MELT`; outside the window the ledger flags `dangling_partial_melt` in `/stats` for operator review. The `LightningRef.partial=true` flag MUST be present on both the `MELT` and the `MELT_REFUND`. |
| `MINT_FAILED`          | empty (`E1`) | empty (`E2`)  | `quote_id` required    | omitted                 | `error_code` required (`X1`)                                                     |
| `RESTORE`              | empty (`E1`) | ≥ 1 (`O1`)    | omitted                | omitted                 | Outputs are recovered proofs. RESTORE events are treated as **graph roots** in the same way MINT events are: standard edge derivation applies, so when a restored proof later appears as an input to a SWAP or MELT, an edge is drawn from the RESTORE event to that consumer. The earlier wording "ledger does NOT compute proof-derived edges from RESTORE outputs unless they later appear as inputs" was clarified per Gemini Round 2 #8 — there is no special case; restored proofs participate in the DAG identically to minted proofs (`R2`). |
| `EVENT_PRUNED`         | n/a          | n/a           | n/a                    | n/a                     | Internal-only; produced by the ledger, never accepted from a producer (`P1`)     |

Cross-cutting invariants (apply to all kinds):

- **`amount` non-negative** (`A0`): every proof and fee amount MUST be ≥ 0; zero is allowed only for fees.
- **`mint_url` consistency** (`M1`): all proofs in `inputs` and `outputs` must reference keysets that the ledger has previously seen advertised by the same `mint_url`. Unknown keysets are accepted but logged at WARN with `unknown_keyset` for operator review (the ledger does not maintain a strict keyset registry).
- **Unit normalisation** (`UN1`): the event's `unit` is the canonical unit for all amount comparisons in that event. `LightningRef.amount` is normalised at the producer to the event's `unit` before publishing — for Lightning operations where the wire protocol uses msat, the producer divides by 1000 to obtain sat (rounding policy: floor). The normalised amount is what `B0` and `B2` compare against. Producers MUST also include the original-unit amount in the `LightningRef.bolt11` decoded form for forensic completeness in `FULL` mode; the ledger does not re-validate the conversion. If the event's `unit` is finer-grained than the Lightning unit (e.g., `msat`), no rounding occurs.
- **Hex encoding** (`H1`): the following fields are lowercase hex with no `0x` prefix; mixed case is rejected: `keyset_id`, `y`, `C`, `payment_hash`, `pubkey`, `event_id`, `redaction_key_id`. The `secret` field is **NOT** required to be hex — per NUT-00 it is an opaque UTF-8 string and per NUT-10/11 may be a structured JSON-encoded string for spending conditions. The `witness` field is similarly an opaque NUT-defined string (typically a JSON object stringified per NUT-11). Producers MUST preserve `secret` and `witness` exactly as the wallet/mint produced them; the ledger does no normalisation. The `Y` derivation `hash_to_curve(secret)` operates on the raw secret bytes regardless of their encoding, so opacity does not affect the join key.
- **Schema version** (`V1`): the event's `schema_version` MUST fall within the ledger's accepted band as defined in §5.12. Currently `N = 1`; the four-tier policy (silent / silent / warn / reject) governs how older and newer values are handled.
- **Output role alignment** (`R3`): if `output_role` tags are present, their count MUST equal `output_y` count and their values must be drawn from `{target, change, fee_return}`.

### 5.10 NUT-00 Wire Format Mapping

The ledger's `content` JSON serialisation of `inputs` and `outputs` MUST use NUT-00 canonical proof field names so that a `FULL`-mode round-trip yields byte-identical proofs (NFR-10). The mapping between Java record fields and JSON keys is:

| Java field       | JSON key   | NUT-00 reference            | Notes                                                  |
|------------------|-----------|------------------------------|--------------------------------------------------------|
| `amount`         | `amount`  | NUT-00 §2.1                 | Integer                                                |
| `keysetId`       | `id`      | NUT-00 §2.1 (keyset id)     | Hex                                                    |
| `y`              | `y`       | derived (`hash_to_curve`)   | Lowercase hex; not part of NUT-00 proof but added here |
| `secret`         | `secret`  | NUT-00 §2.1                 | Present only in FULL                                   |
| `c`              | `C`       | NUT-00 §2.1 (capital C)     | Present only in FULL                                   |
| `witness`        | `witness` | NUT-10 / NUT-11 / NUT-14    | Stringified JSON per NUT-11                            |
| `dleq`           | `dleq`    | NUT-12                      | Object with keys `e`, `s`, optional `r`                |

A `FULL`-mode proof emitted by the ledger satisfies: stripping the `y` key yields a byte-identical NUT-00 proof to what the producer hashed for `Y`.

#### Bundle Token Handling (V4)

`SEND` and `RECEIVE` events MAY carry the raw `cashuB` token bundle the user actually shipped, so auditors can verify byte-for-byte that the trace event matches what was transmitted out-of-band. The bundle token is a NUT-00 v4 CBOR-encoded blob carrying the mint URL, unit, optional memo, and the proof list.

| Aspect                      | Rule                                                                                                                                                                                                                                                                                                                                                                |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Storage location            | Optional `bundleToken` field in the event's `content` JSON. NOT a Nostr tag — token sizes can exceed practical tag-size budgets and tags are designed for indexable scalars, not blobs.                                                                                                                                                                              |
| Privacy-mode gating         | Present in `FULL` only. The CBOR bundle includes per-proof `secret` and `C`, so it is FULL-mode-only data. `HASHED` and `MINIMAL` events MUST omit the field entirely; the producer SDK refuses to publish a HASHED/MINIMAL `SEND`/`RECEIVE` with a non-null `bundleToken` (`TRACE_PRIVACY_VIOLATION`).                                                              |
| Token format                | V4 (`cashuB...`) only. V3 (`cashuA...`, JSON-encoded) is legacy; the producer SDK refuses to publish a V3 token with `TRACE_LEGACY_TOKEN_FORMAT`. Operators carrying V3 traffic MUST upgrade their wallet/mint to emit V4 before adopting bundle-token capture.                                                                                                       |
| Ingest verification         | The ledger re-parses the CBOR on ingest and asserts the parsed proof list (mint URL, unit, ordered `(keysetId, amount, secret, C)` tuples) equals the event's `inputs`. Mismatch → reject with `INVALID_OPERATION` sub-code `B1_BUNDLE_MISMATCH`. Re-parsing makes the raw a verifiable artefact, not an opaque blob, at the cost of one CBOR parse per SEND/RECEIVE. |
| Size limit                  | 64 KB per raw token. Larger tokens rejected at ingest with `TRACE_BUNDLE_TOO_LARGE`. Operators tuning for unusual workloads can override the cap via `trace.bundle-token.max-bytes`; the default is conservative.                                                                                                                                                    |
| CBOR parser                 | Reuse the existing `cashu-java` v4 parser. Defensive limits enforced at ingest: max nesting depth 8, the size cap above, reject unknown CBOR major types. No new parser implementation in this project — keeps the security-review surface bounded.                                                                                                                  |
| Read API                    | `GET /events/{id}?include=parsed` (default) returns the structural proof view only. `?include=raw` adds `bundleTokenRaw` (base64url-encoded) to the response, requires `trace:read:full`, and the underlying event must have been published in `FULL` mode. `?include=both` returns parsed structure plus `bundleTokenRaw`.                                          |
| Tombstones                  | When a `SEND`/`RECEIVE` event is pruned (§5.11), the `bundleToken` is gone with the rest of the payload. The tombstone retains `bundle_id` so the SEND↔RECEIVE link survives, but the raw token is unrecoverable — operators who anticipate audit recall MUST archive raw events before pruning, as the existing pruning rule already stipulates.                    |

### 5.11 Pruning and Tombstones

`EVENT_PRUNED` is the audit kind emitted when a retention policy removes a payload. It is the only mechanism by which trace data is ever removed; "editing or deleting events" in Section 11 refers to producer-initiated changes, which remain out of scope.

Pruning semantics:

- A prune **deletes the entire raw signed Nostr event** (the kind-9079 event) from nostrdb. The signed event is the system of record; modifying its content would invalidate the signature. Operators who anticipate pruning MUST archive the raw events off-system before pruning; the ledger does not retain them.
- The ledger then **stores a tombstone**: a small ledger-internal record (NOT a signed Nostr event) keyed by the pruned `event_id` with these fields: `pruned_event_id`, original `d` tag (operation_id), original `mint_url`, original `producer_pubkey`, original `transition_at`, original `op`, the original `(keyset_id, Y)` tuples for `input_y` and `output_y`, original `bundle_id` / `voucher_ref` / `quote_id` if present, and a `pruned_at` timestamp. The tombstone is stored in a SQLite tombstones table, NOT in nostrdb, because it is not a signed event.
- Tombstones occupy a separate REST surface: walk responses include them as `{type: "tombstone", prunedEventId: "...", op: "swap", inputYs: [...], outputYs: [...]}` so the front-end can render an opaque node where a pruned event used to be.
- The original `d` tag value is **NOT** reused for any new signed event — preserving idempotency of the producer-side `traceability_op` mapping. If a producer accidentally re-publishes an event whose original was pruned, the ledger detects it via the tombstone's preserved `d` and rejects with `OPERATION_PRUNED`.
- **SQLite sidecar rows** for proof-derived join keys are preserved and updated to point at the tombstone, NOT at the deleted Nostr event id, ensuring walks still traverse pruned hops via the `(keyset_id, Y)` index.
- Walks across pruned ancestors return tombstones for the pruned hops and continue past them; the response includes `pruned_count` so consumers know how many opaque hops were involved.
- Pruning a `*_QUOTE_REQUESTED` event cascade-prunes its associated `MINT`/`MELT`/`*_FAILED` event ONLY if both are within the retention boundary; otherwise the pruned quote becomes a dangling reference flagged in the API response.
- Pruning is irreversible. Operators retain raw event archives off-system if they need recoverability.
- Pruning emits a `prune_completed` log line with batch counts for audit.
- **Sub-DAG terminality pruning (decided).** In addition to per-event age-based pruning, the retention engine MAY prune **fully terminal sub-DAGs** ahead of the per-event age threshold. A sub-DAG is "fully terminal" when every leaf event has `activity=TERMINAL` per §5.4.1 (e.g., every leaf is a `MELT`, `MELT_FAILED`, `MINT_FAILED`, `EVENT_PRUNED`, or has its activity flipped via the voucher-state watcher or all-outputs-spent rule). Sub-DAG pruning reuses the activity cache; no new graph walk is required at prune time. Rationale: terminal sub-DAGs have minimal forensic value once retention pressure rises — the value has already moved through and is accounted for elsewhere — while active sub-DAGs (where downstream events may still arrive) need to remain whole to support investigations. Operators who want age-only pruning can disable the sub-DAG mode via `trace.pruning.terminal-subdag.enabled=false`. The spec does not mandate one strategy over the other; both are supported and configurable.
- **Activity-cache dependency.** Sub-DAG pruning reads `activity` from the §5.4.1 cache. If the activity cache is stale (e.g., the voucher-state watcher is offline per §6.4), sub-DAG pruning is paused — the engine falls back to age-based pruning until the cache catches up. This avoids over-pruning sub-DAGs that look terminal only because the cache hasn't seen a recent state change. Pause is logged at WARN with `subdag_pruning_paused reason=activity_cache_stale`.
- **Storage recovery is partial.** Pruning reclaims the bulky nostrdb event payload (the bulk of per-event size: secrets, witnesses, content JSON), but the ledger MUST retain the tombstone record AND the SQLite sidecar index rows for proof-derived join keys (`input_y`, `output_y`, `mint_url`, `producer_pubkey`, `bundle_id`, `voucher_ref`, `quote_id`) indefinitely so the DAG remains traversable. For a high-fan-in/out event (e.g., a SWAP with 64 inputs and 64 outputs), the retained sidecar rows can dominate post-prune storage. Operators planning aggressive pruning policies MUST size sidecar storage assuming **256 bytes per surviving join-key row × number of inputs+outputs per pruned event**. A pruned 64-in/64-out SWAP retains roughly 32 KB of sidecar metadata even after the nostrdb payload is gone.



- The `/visualisation` graph endpoint NEVER returns secret-bearing fields; the front-end starts with a redacted-by-default view.
- Drill-down requires an **explicit user action** ("Reveal secret payload" button) per node; revealed payloads are not auto-cached by the front-end and are evicted when the panel closes or the user navigates away. For `SEND`/`RECEIVE` events the drill-down panel adds a secondary "Show raw token" tab next to the parsed view, fetching `?include=raw`; the same eviction, banner, and audit-log rules apply.
- The UI SHOULD display a banner each time a secret is rendered: "Showing FULL payload — do not screenshot or share."
- The browser MUST set `Cache-Control: no-store` on all responses from `/api/v1/trace/events/*` to prevent intermediate caches from retaining secrets.
- Each drill-down request to `/api/v1/trace/events/{eventId}` is recorded in the access log (Section 7.4) with `payload_revealed=true`, so operators can audit who looked at what.
- Authority gating: a caller without `trace:read:full` receives the `HASHED` or `MINIMAL` shape regardless of which endpoint they hit; the front-end can only render what the API returns.
- Browser local storage and indexedDB MUST NOT be used for trace event payloads; the front-end keeps revealed payloads in JS heap memory only.

### 5.12 Schema Evolution Policy

The `schema_version` tag (§5.3) is the single knob that lets the producer SDK and the ledger evolve independently. This subsection defines the four-tier compatibility ladder and the rules for when the version bumps.

**Compatibility ladder.** Let `N` be the ledger's `currentSchemaVersion` (the highest version it knows how to fully validate). On ingest, an event's declared `schema_version` is handled as follows:

| Incoming `schema_version` | Ledger behaviour                                                                                                                                                                                                                                                                            |
|---------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `N`                       | Accept silently. Validation proceeds against the current schema rules.                                                                                                                                                                                                                       |
| `N-1`                     | Accept silently. The ledger maintains read/write compatibility for one full generation.                                                                                                                                                                                                      |
| `N-2`                     | Accept; emit `TRACE_DEPRECATED_SCHEMA` at WARN with the event id and version. Read responses involving the event include `schemaDeprecated: true` so consumers can flag stale rows.                                                                                                          |
| `≤ N-3`                   | Reject at ingest with `TRACE_UNSUPPORTED_SCHEMA`. Already-indexed events at that version remain readable; no new events at that version are admitted. Operators who need to ingest such events must temporarily run an older ledger build or re-publish under a supported schema version.   |
| `> N`                     | Reject at ingest with `TRACE_FUTURE_SCHEMA`. The producer SDK is ahead of the ledger; the operator MUST upgrade the ledger before the producer can deliver events at this version.                                                                                                          |

**Producer SDK guard.** The SDK refuses to start when `compileTimeSchemaVersion ∉ ledger.supportedSchemaVersions ∧ compileTimeSchemaVersion > ledger.currentSchemaVersion + 1`. The `+1` window allows a producer to be deployed one version ahead of the ledger during a coordinated rollout (the ledger ingests `N+1` only after its own upgrade lands).

**What triggers a `schema_version` bump.** Bumps are reserved for genuinely breaking changes:

- A tag is renamed (e.g., `producer_pubkey` → `attestor_pubkey`).
- A field's semantic interpretation changes (e.g., `fee` recast from "actually charged" to "estimated").
- An operation kind enum value is **removed** (kind additions are additive — see below).
- A canonicalisation rule changes in a way that alters the deterministic event id derivation (tag ordering, JCS ruleset, integer encoding).

The following are **additive** and MUST NOT bump the version:

- A new optional tag on existing kinds. Unknown tags MUST be preserved by the ledger and round-tripped on `FULL`-mode reads (§5.3 already mandates preservation; this is a normative restatement).
- A new operation kind (e.g., a future `STAKE` kind). Older ledgers that do not recognise the kind MUST drop the event with `TRACE_UNKNOWN_KIND` rather than rejecting the entire schema version, and MUST emit a metric so the operator notices.
- A new error code value in `error_code` for an existing kind.
- A new value for `output_role` that older ledgers can index opaquely.

**Required release artefacts on every bump.** Every bump from `N` to `N+1` MUST include:

1. A `Changed` and (where applicable) `Removed` entry in `CHANGELOG.md` naming the affected tags / fields / kinds.
2. A migration note appended to this spec's Revision Log explaining what the bump enables and what producers must do.
3. A sidecar index re-derivation only if the bump affects an indexed column. For purely tag-level renames that do not alter `(keyset_id, Y)`, `mint_url`, `producer_pubkey`, `bundle_id`, `voucher_ref`, `issuer_id`, `issuer_pubkey`, `quote_id`, `activity`, or `transition_at`, no re-derivation is required. For changes that do affect these columns, the migration note MUST cite the rebuild procedure (§5.4 describes the online rebuild path).
4. A test double in `cashu-ledger-trace-core` that produces a synthetic `N-1` event so regression tests for the four-tier ladder do not bit-rot.

**Discovery.** `GET /relays` (§5.5) advertises `currentSchemaVersion`, `supportedSchemaVersions` (the inclusive band the ledger accepts), and `deprecatedSchemaVersions` (the subset that triggers `TRACE_DEPRECATED_SCHEMA`). Producer SDKs SHOULD refresh this view at startup and on any `TRACE_DEPRECATED_SCHEMA` or `TRACE_FUTURE_SCHEMA` response from a publish attempt; SDKs MUST NOT cache the discovery response longer than 1 hour.

**Cross-references.** `NFR-9` (§4.5) requires the `schema_version` tag mechanism; this subsection defines the policy. `V1` invariant (§5.9) is the per-event check. The `/relays` response shape (§5.5) advertises the versions to producers.

## 6. Architecture

### 6.1 High-Level Component Diagram

```
                Producer side (mint/wallet backends)
       ┌────────────────────────────────────────────────────┐
       │  Wallet/Mint code  ─→  TraceabilityPublisher SDK   │
       │                              │                     │
       │                              ▼                     │
       │                        OutboxStore (sqlite)        │
       │                              │                     │
       │                              ▼                     │
       │                       OutboxDispatcher (async)     │
       │                              │                     │
       │                              ▼                     │
       │                       nostr-java NostrClient       │
       └──────────────────────────────┼─────────────────────┘
                                      │ ws / wss
                                      ▼
                                Nostr relays
                                      │
       ┌──────────────────────────────┼─────────────────────┐
       │  Ledger side                 ▼                     │
       │   TraceSyncEngine ─→ EventStore (nostrdb + index)  │
       │           │                         ▲              │
       │           │                         │              │
       │           ▼                         │              │
       │   TraceQueryService ←── REST API ───┤              │
       │           │                         │              │
       │           │            VoucherStateWatcher (kind   │
       │           │            30078 sub) ──→ activity     │
       │           │            cache invalidation (§5.4.1) │
       │           ▼                         │              │
       │   Graph + drill-down (cashu-ledger-web)            │
       └────────────────────────────────────────────────────┘
```

### 6.2 Module Layout

Adds two modules and modifies three:

- **NEW** `cashu-ledger-trace-core` — domain model (`OperationKind`, `ProofRef`, `TransactionEvent`, `PrivacyMode`, `LightningRef`, `DleqProof`, `TraceEventStore` interface, canonical JSON serialiser). The single source of truth for schema DTOs (Section 5.1). Sits next to `cashu-ledger-core`. Depends only on Jackson and slf4j; no Spring, no Lombok, no Nostr.
- **NEW** `cashu-ledger-trace-publisher` — producer SDK. Depends on `cashu-ledger-trace-core`, `nostr-java`, Lombok, Jackson, SLF4J, optionally Spring Boot autoconfigure.
- `cashu-ledger-core` — extended with `TraceEventStore` implementation backed by nostrdb, a `TraceSyncEngine` analogous to `ClientSyncEngine`, and the `VoucherStateWatcher` (Section 6.5) that subscribes to kind-`30078` voucher events and feeds the activity-cache invalidator.
- `cashu-ledger-web` — adds REST controllers for `/api/v1/trace/*`, the `/trace` page, and SSE streaming for new events.
- `cashu-ledger-cli` — adds the `trace` subcommand tree.
- `cashu-ledger-integration-tests` and `cashu-ledger-e2e-tests` — extended with traceability scenarios (Section 8).

### 6.3 Threading and Backpressure

- Producer-side dispatcher is a single-threaded `ScheduledExecutorService`. The polling cadence is **adaptive**:
  - Default cadence: 250 ms poll, batch size 64. Throughput at this cadence is approximately 256 events/s — adequate for steady-state.
  - **High-water mark trigger**: when outbox depth exceeds 5 000 rows OR the moving-average enqueue rate exceeds 200 events/s for 10 seconds, the dispatcher switches to "fast mode": 50 ms poll, batch size 256. This sustains ≥ 5 000 events/s, comfortably above the 500 events/s burst goal in NFR-3.
  - **Low-water mark trigger**: when depth drops below 500 rows AND the enqueue rate falls below 50 events/s for 30 seconds, the dispatcher returns to default cadence.
  - The producer SDK exposes the current mode via a metric (`outbox_dispatcher_mode`) so operators can correlate.
- Ledger-side sync engine subscribes to relays via `nostr-java`. Ingestion is sharded by `mint_url` hash to two worker threads by default; this can be tuned to up to 8 threads for high-volume deployments.
- **Write-ahead reconciliation across nostrdb + SQLite sidecar.** A true atomic transaction across nostrdb (LMDB-backed) and SQLite is not generally available; the spec instead defines a write-ahead protocol with idempotent reconciliation:
  1. Compute the canonical event id and check if it already exists in either store. If both present, no-op (idempotent).
  2. Write the raw event to nostrdb first (the system of record).
  3. Write the index projections to the SQLite sidecar with `INSERT OR IGNORE`. SQLite writes are idempotent — re-running them after a crash yields the same state.
  4. If the sidecar write fails, log `index_write_failed event_id=...` at ERROR and queue the event id in a `pending_index` table for retry by a background reconciler that runs every 30 s.
  5. The reconciler iterates `pending_index`, attempts the SQLite write again, and removes the row on success.
  6. On startup, the ledger compares the latest nostrdb event id timestamp to the SQLite sidecar's max `transition_at`; any nostrdb events newer than the sidecar are re-projected by the reconciler. This is the rebuild path also used after sidecar corruption (Section 5.4).
- The ledger exposes an **index lag metric** (`trace_index_lag_seconds`) showing the gap between nostrdb's newest event and the sidecar's newest indexed event. If lag exceeds a configurable threshold (default 30 s), the ledger surfaces a `503 INDEX_LAGGED` for queries that depend on the missing rows; queries that hit only by event id continue to succeed against nostrdb directly.

### 6.4 Failure Modes and Degradation

| Failure                          | Behaviour                                                                 |
|----------------------------------|---------------------------------------------------------------------------|
| Relay unavailable (producer)     | Outbox retains events; retries with backoff; user-facing op unaffected    |
| Outbox full (producer)           | Active overflow policy applies (FR-11a). Default `BLOCK_AND_ALERT` blocks up to 250 ms; if drain does not occur, throws `TraceabilityPublishException` to the caller. `DROP_*` policies log structured `TRACE_DROPPED` audit lines and increment metrics. `FAIL_OPEN` returns success without enqueueing. The user-facing operation is affected ONLY under `BLOCK_AND_ALERT` and ONLY when overflow persists. |
| Ledger nostrdb full              | Sync engine stops accepting new events; alerts via metrics; reads continue from existing data |
| Conflicting event (same op id)   | Ledger keeps the first; emits `OPERATION_CONFLICT` audit log              |
| Out-of-order arrival             | Acceptable; DAG reconstruction is order-independent because edges derive from `y` matches |
| Producer clock skew > tolerance  | Ledger rejects with `CLOCK_SKEW`; producer must resync NTP                |
| Walk explosion (huge fan-out)    | `max-walk-nodes` enforced; response truncates with cursor                 |
| Voucher-state watcher offline    | Activity cache stops invalidating on voucher transitions (Section 5.4.1 trigger 2); other invalidation triggers continue to apply. The cache becomes increasingly stale for the `voucher_terminal` reason; `/stats` surfaces `voucher_watcher_lag_seconds` and the sidecar continues to serve filter queries with the warning header `Stale-Activity: voucher_terminal`. Recovery: on watcher restart, replay missed voucher events and bulk-update affected trace events. |

### 6.5 Voucher State Watcher

The activity cache (Section 5.4.1) requires near-real-time visibility into voucher state transitions. A new `VoucherStateWatcher` component lives in `cashu-ledger-core` and:

- Subscribes to kind-`30078` voucher events on the same Nostr relay set the trace ledger uses (avoids a separate connection).
- Maintains an in-memory map `voucher_d_tag → VoucherStatus` populated from the latest replaceable voucher event (kind 30078 is parameterised-replaceable, so the latest by `created_at` wins per `pubkey + d`).
- On every transition into a terminal status (`isTerminal()` true per `VoucherStatus`), enqueues an invalidation job that runs `UPDATE events_index SET activity='terminal', activity_reason='voucher_terminal', activity_changed_at=? WHERE voucher_ref=?`.
- Persists its high-watermark (last processed voucher `created_at`) to a small SQLite table `voucher_watcher_cursor` so a restart does not re-process the entire voucher history.
- Exposes a metric `voucher_watcher_lag_seconds` (newest voucher event observed minus newest voucher event processed) and a Prometheus counter for terminal transitions applied.

**Why a separate watcher and not the existing `VoucherStateJournal`?** The journal in `cashu-ledger-core` is read-on-demand and tied to the voucher API. The trace ledger needs push-based invalidation so that filter queries are correct without a per-row check. The watcher is a thin subscriber over the same `EventStore`; it does not duplicate journal logic, only forwards terminal-state edges to the activity cache.

**Failure isolation.** Watcher failures MUST NOT break trace ingest or read paths. If the watcher is offline, activity remains correct for all reasons except `voucher_terminal`, and the failure mode in §6.4 governs the user-visible degradation.

## 7. Security and Privacy

### 7.1 Threat Model

**Assets:**
- Proof secrets (allow spending the proof; equivalent to a bearer token).
- Lightning preimages and bolt11 strings (link Cashu activity to Lightning identifiers).
- Linkability of proofs across operations (defeats Cashu's anonymity property).

**Adversaries:**
- External attacker on the public internet (cannot reach the ledger; must be enforced).
- Compromised relay (sees all events the producers publish).
- Compromised actor pubkey (can sign forged events for that actor).
- Curious internal user who has REST credentials but should not see all data.

**Mitigations:**
- Default deployment uses a **private, authenticated relay** (`relay.imani.casa` configured with NIP-42 auth or IP allow-list).
- All REST endpoints require NIP-98 auth; authorisation rules in Section 7.3.
- Privacy modes (Section 7.2) reduce exposure when full secrets are unnecessary.
- Producer signing keys are per-mint or per-wallet-backend; rotation supported via NIP-26 delegation tags.
- Audit log of ledger reads (Section 7.4).
- **Producer attestation (decided).** The ledger MUST reject any trace event whose signing pubkey is not registered as an authorised producer for the `mint_url` in the event. Producer attestation is configured in `trace.producers`:

  ```yaml
  trace:
    producers:
      - mint_url: "https://mint.imani.casa"
        signers: [ "<mint_signer_pubkey_hex>", "<wallet_backend_signer_pubkey_hex>" ]
        delegation: allowed   # accept NIP-26 delegated signatures
  ```

  Rejected events emit `TRACE_FORBIDDEN_PRODUCER` and are dropped before sidecar indexing. The `producer_pubkey` tag MUST equal the event `pubkey` unless a NIP-26 delegation tag is present and references the producer pubkey, in which case the delegation MUST also be valid for kind `9079`. The `initiator_pubkey` tag (when present) is informational and is NOT cryptographically validated by the ledger — the producer attests on the initiator's behalf, which is acceptable because the producer is itself an authenticated, attested signer. (Open Question 9 from Round 1 is closed by this mitigation.)

### 7.2 Privacy Posture (in tension with Cashu)

Cashu's design assumes the mint cannot link sender and receiver. **Capturing the chain of custody operationally undoes that property for the operator.** This is acceptable inside an operator boundary (a single mint operator inspecting their own mint) but unacceptable as a public service.

Specification stance:

- The ledger is **operator-internal regardless of privacy mode**. Even HASHED and MINIMAL modes leak `Y` (Section 5.2 caveat), which is sufficient to confirm membership of specific secrets if an adversary has candidate plaintexts. The privacy modes therefore reduce — but do not eliminate — exposure if the ledger or its relays are compromised.
- All deployments MUST run against a **private, authenticated relay** (NIP-42 auth, IP allow-list, or VPN-only). Public Nostr relays are forbidden as the publish target for any privacy mode. The producer SDK MUST refuse to publish unless every relay in its configured publish set is annotated `private=true` in producer config; this applies in all modes (FULL, HASHED, MINIMAL). Attempting to start a publisher with even one non-private relay configured is a fatal startup error (`PRIVATE_RELAY_REQUIRED`).
- For shared relays, producers MUST use `HASHED` or `MINIMAL`. The HMAC redaction (Section 5.2) makes hashed values unlinkable to outsiders without the `redaction_key`.
- A compromised relay (one of the explicit threats in Section 7.1) sees only what the producers publish at the configured privacy mode. The combination of (a) `FULL` requires a private relay set, and (b) HASHED/MINIMAL exposes only HMAC-redacted or omitted secrets, addresses the "compromised relay" threat without requiring application-layer encryption to the ledger.
- An optional **NIP-44 encrypted content** mode is reserved for a future spec revision: events would be addressed to the ledger's pubkey and decrypted on ingest, allowing `FULL` mode over partially-trusted relays. This is not part of v1 — it is recorded as deferred work in Section 11. **When adopted, the ledger MUST use per-day rotating session keys, not a single long-term key.** Rationale: a long-term key compromise reveals the entire encrypted history; per-day rotation bounds the blast radius to one day's events. Each day's key is generated at midnight UTC, and the prior day's key is retained read-only for a configurable retention window (default 30 days) so that ingested events remain decryptable for retrospective review. Producer SDKs encrypting outgoing events MUST use the ledger-advertised current-day key; the `GET /relays` discovery endpoint is the natural carrier for the active key id (the key material itself is fetched via a separate authenticated channel — TBD in the v2 spec).
- The web visualisation UI MUST display the privacy mode banner prominently.
- Rendering and drill-down MUST never include `secret` / `C` / `witness` / `bolt11` for users without the `trace:read:full` authority (Section 7.3).
- **Issuer fields are not privacy-sensitive at the ledger boundary.** `issuer_id` and `issuer_pubkey` are returned verbatim to all read authorities (`trace:read:summary`, `trace:read:hashed`, `trace:read:full`) because the same values are already public in the underlying voucher event. Operators concerned about exposing merchant→activity correlation to summary-only auditors should restrict access to the trace API at the authority level rather than rely on field-level redaction. This is a deliberate departure from the otherwise privacy-mode-driven serialisation in Section 5.2.

### 7.3 Authorisation

NIP-98 establishes the caller pubkey. The ledger maps pubkeys to authorities through a small static config:

```yaml
trace:
  authorities:
    - pubkey: "<operator_pubkey_hex>"
      grants: [ trace:read:full, trace:admin ]
    - pubkey: "<auditor_pubkey_hex>"
      grants: [ trace:read:summary ]
```

Authority semantics:

- `trace:read:summary` — may call all read endpoints; receives `MINIMAL`-shaped responses regardless of stored mode.
- `trace:read:hashed` — receives `HASHED`-shaped responses.
- `trace:read:full` — receives `FULL` payloads.
- `trace:admin` — may call `/stats` deeper queries, request prune, and replay backfills.

If a caller has no authority, requests return `403 TRACE_FORBIDDEN`.

### 7.4 Audit Trail of Ledger Access

- Every authenticated read MUST be logged at INFO with `actor`, `endpoint`, `anchor`, and `result_size`. Logs are structured key-value (per project logging conventions).
- The ledger exposes a `/api/v1/trace/admin/access-log` endpoint (admin only) that streams the access log for compliance review.

### 7.5 Sanitised Export for External Sharing

Operators occasionally need to share a partial trace with an external auditor or counterparty without exposing the deployment's long-term `redaction_key`. The spec provides a **sanitise tool** that produces a self-contained export package using a one-shot ephemeral redaction key. The operator's `redaction_key` never leaves the deployment.

**Workflow.**

1. Operator selects an anchor (proof, voucher, issuer, transfer, time range) and runs `cashu-ledger trace export <anchor> --sanitise --output <dir>`.
2. The tool generates a fresh 256-bit ephemeral HMAC key (`ephemeral_key`) using the platform CSPRNG. The key is unique per export.
3. The tool walks the export and re-redacts every secret-bearing field using `ephemeral_key` instead of the deployment's `redaction_key`. The redaction recipe is the same as §5.2 / §7.5 (HMAC-SHA-256, identical domain separation per field), so cross-event joins on the redacted `Y` and other join keys remain verifiable within the export.
4. The tool emits two artefacts:
   - `export.json` — the sanitised events. Privacy mode is forced to `HASHED` regardless of the source events' modes; `redaction_key_id` on every event is overwritten to a new value (`ephemeral-<random_8>` so it is visually distinct from production key ids).
   - `ephemeral_key.bin` — the 32-byte raw ephemeral key.
5. The operator delivers the two artefacts to the auditor via separate channels (e.g., signed email + secure messenger). The export is useless without the key, and the key is useless without the export.

**Verification by the auditor.** The auditor uses `ephemeral_key.bin` together with `export.json` to re-derive HMACs and verify that the proof-join graph is internally consistent. The auditor cannot:

- Correlate the export against any other operator data (different key).
- Dictionary-attack secrets across the operator's full ledger (the operator's `redaction_key` was never used).
- Reuse the key against any future operator export (each export uses a fresh key).

**Privacy contract.**

- Operator's deployment-scoped `redaction_key` MUST NOT be used to produce a sanitised export. The sanitise tool refuses if it is configured against the production `redaction_key`; the only way to re-redact is with a freshly generated `ephemeral_key`.
- Sanitised exports MUST carry a top-level `sanitised: true` flag and the `ephemeral_key_id` value, so that audit logs and downstream tooling can distinguish them from raw ledger output.
- `FULL`-mode round-trip is not preserved through sanitisation. The export is intentionally one-way; an auditor cannot reconstruct the original `secret` or `C` even with the ephemeral key.
- The raw `cashuB` token bundle (§5.10 Bundle Token Handling) is **stripped entirely** during sanitisation — there is no privacy-preserving way to redact a CBOR token while keeping its proof set intact, and the parsed proof structure already provides the auditable view.

**Audit logging.** Every sanitised export is logged at INFO with `sanitise_completed event_count=N anchor=<anchor> ephemeral_key_id=<id>` and the access entry recorded per §7.4. The operator's runbook MUST cover (a) verifying the auditor's identity before delivery, (b) selecting separate channels for the export and the key, and (c) revoking the export by simply destroying the ephemeral key file (the export becomes opaque without it).

(Closes Open Question 7.)

### 7.6 Cryptographic Considerations

- Producer signs the Nostr event with secp256k1 Schnorr per NIP-01.
- `Y = hash_to_curve(secret)` per Cashu NUT-00 / NUT-11. The implementation MUST use the same curve, domain separation, and hash function as the wallet to ensure consistent join keys.
- Privacy-mode redaction uses **HMAC-SHA-256** under the deployment-scoped `redaction_key` (Section 5.2). Bare SHA-256 is **not** used for any secret-bearing field; it is reserved only for the Nostr event id calculation per NIP-01. (Earlier draft text saying "Privacy-mode hashes use SHA-256" is corrected here.)
- The `redaction_key` is a 256-bit secret and is the only key custody requirement on the producer side beyond the Nostr signing key. Ledger reads do not require any key.
- Producer attestation (Section 7.1) requires the ledger to maintain a static config of authorised signer pubkeys per mint URL. Optional NIP-26 delegations from those signers are honoured.

## 8. Testing Strategy

### 8.1 Unit Tests (cashu-ledger-trace-core, -publisher)

- `ProofRef` redaction under each `PrivacyMode` produces the expected field set.
- Canonical JSON serialisation: same logical event produces byte-identical content given different field insertion orders.
- `OperationKind` round-trip from tag to enum and back.
- Outbox enqueue/dequeue, retry timing, max-retention eviction.
- Idempotency: publishing twice with the same operation_id results in one outbox row.
- Producer SDK clock skew guard.
- Edge derivation: given a list of stored events, reconstruct upstream/downstream neighbour sets correctly across MINT → SWAP → SEND chains.

### 8.2 Integration Tests (cashu-ledger-integration-tests)

Use Testcontainers for a single-relay environment.

- End-to-end publish-and-query: producer publishes a SWAP, sync engine ingests, REST `/proofs/{y}` returns the right event.
- Privacy mode: publishing in `HASHED` mode is queryable but never returns raw secrets, regardless of caller authority.
- Backfill: publisher emits an event with `transition_at = now - 7d`; ledger accepts only with `--allow-historical`.
- Replay rejection: re-publish the same operation; second copy is dropped with no DAG changes.
- Walk truncation: build 5,000 chained events, request walk with `limit=100`, verify `truncated=true` and that the cursor enables continuation.
- Conflict: publish two distinct events with the same `traceability_op` tag; second is rejected.
- Cross-link with voucher events: publish a `SEND` with `voucher_ref`; voucher inspect surfaces the SEND in `transactionEvents`.

### 8.3 E2E Tests (cashu-ledger-e2e-tests)

Use the staging stack (mint, relay, ledger web, ledger CLI).

- Mint a token via the wallet integration → assert MINT event appears in CLI within 30 s.
- Swap → assert input/output Y values match the proofs returned by the wallet.
- Melt failure → assert MELT_FAILED event with the correct error code.
- Render `/trace` page in headless Chromium, anchor on a known event, assert node count matches REST.
- Drill-down panel displays full secrets only when authenticated as the operator pubkey.

### 8.4 Property-Based Tests

Using jqwik:

- For a random forest of synthetic transaction events with consistent `Y` linkage, the walk algorithm reaches every node a BFS reaches and no others.
- Canonical JSON is order-invariant: shuffling input field order yields the same content bytes.

### 8.5 Performance Tests

- 1,000 events ingested over 20 s; observe p99 ingest-to-visible lag < 30 s.
- Walk over a 10,000-node graph completes in < 250 ms p95 from cold cache, < 50 ms warm.

## 9. Implementation Phases

Each phase is independently shippable. Tasks track in the same per-task table style as the existing spec, with status and commit columns.

### Phase T1: Foundations

| ID    | Task                                                                                  | Size | Depends On | Status  | Commit |
|-------|---------------------------------------------------------------------------------------|------|------------|---------|--------|
| T1.1  | Add `cashu-ledger-trace-core` module with `OperationKind`, `ProofRef`, `TransactionEvent`, `PrivacyMode` records | M    | -          | Pending | -      |
| T1.2  | Define Nostr event schema (kind 9079) + canonical JSON serialiser                      | M    | T1.1       | Pending | -      |
| T1.3  | Reserve / document the kind via internal ADR; add `schema_version` plumbing            | S    | T1.2       | Pending | -      |
| T1.4  | Unit tests for canonical serialisation, redaction, deterministic event id              | M    | T1.2       | Pending | -      |

### Phase T2: Producer SDK

| ID    | Task                                                                                  | Size | Depends On  | Status  | Commit |
|-------|---------------------------------------------------------------------------------------|------|-------------|---------|--------|
| T2.1  | Add `cashu-ledger-trace-publisher` module with `TraceabilityPublisher` API            | M    | T1.x        | Pending | -      |
| T2.2  | `OutboxStore` abstraction + `InMemoryOutboxStore` + `SqliteOutboxStore`                | L    | T2.1        | Pending | -      |
| T2.3  | `OutboxDispatcher` with backoff and metrics                                            | M    | T2.2        | Pending | -      |
| T2.4  | Spring Boot autoconfigure starter for the publisher                                   | S    | T2.3        | Pending | -      |
| T2.5  | OpenTelemetry instrumentation                                                          | S    | T2.3        | Pending | -      |

### Phase T3: Ledger Storage and Sync

| ID    | Task                                                                                  | Size | Depends On  | Status  | Commit |
|-------|---------------------------------------------------------------------------------------|------|-------------|---------|--------|
| T3.1  | Implement `TraceEventStore` (nostrdb + SQLite sidecar) with kind-9079 indexes          | L    | T1.x        | Pending | -      |
| T3.2  | `TraceSyncEngine` mirroring the `ClientSyncEngine` pattern                            | L    | T3.1        | Pending | -      |
| T3.3  | Cross-link with voucher events: index `voucher_ref` and surface in voucher detail     | M    | T3.1        | Pending | -      |
| T3.4  | `EVENT_PRUNED` audit emission and configurable retention                              | M    | T3.1        | Pending | -      |

### Phase T4: Read API

| ID    | Task                                                                                  | Size | Depends On  | Status  | Commit |
|-------|---------------------------------------------------------------------------------------|------|-------------|---------|--------|
| T4.1  | `TraceQueryService` in `cashu-ledger-core`                                            | L    | T3.x        | Pending | -      |
| T4.2  | REST controllers under `/api/v1/trace` in `cashu-ledger-web` with NIP-98 auth         | L    | T4.1        | Pending | -      |
| T4.3  | Authority resolution (`trace:read:summary` / `:hashed` / `:full` / `trace:admin`)     | M    | T4.2        | Pending | -      |
| T4.4  | SSE endpoint `/api/v1/trace/stream` for live ingestion notifications                  | M    | T4.2        | Pending | -      |
| T4.5  | OpenAPI spec generation                                                                | S    | T4.2        | Pending | -      |

### Phase T5: CLI

| ID    | Task                                                                                  | Size | Depends On  | Status  | Commit |
|-------|---------------------------------------------------------------------------------------|------|-------------|---------|--------|
| T5.1  | `cashu-ledger trace event/operation/proof/events/voucher/quote/stats` subcommands      | L    | T4.1        | Pending | -      |
| T5.2  | `trace export` with json/csv/graphml                                                  | M    | T5.1        | Pending | -      |
| T5.3  | ASCII DAG renderer for `--output tree`                                                | M    | T5.1        | Pending | -      |

### Phase T6: Web Visualisation

| ID    | Task                                                                                  | Size | Depends On  | Status  | Commit |
|-------|---------------------------------------------------------------------------------------|------|-------------|---------|--------|
| T6.1  | `/trace` page skeleton, anchor input, filter rail                                     | M    | T4.x        | Pending | -      |
| T6.2  | Cytoscape.js graph rendering with dagre layout                                        | L    | T6.1        | Pending | -      |
| T6.3  | Drill-down panel with full payload (mode-respecting)                                  | M    | T6.2        | Pending | -      |
| T6.4  | "Anchor here" / "Expand neighbours" / "Truncated" UX                                   | M    | T6.2        | Pending | -      |
| T6.5  | Live updates via SSE                                                                  | M    | T4.4, T6.2  | Pending | -      |
| T6.6  | Privacy banner and authority-aware UI degradation                                     | S    | T6.3        | Pending | -      |

### Phase T7: Operations

| ID    | Task                                                                                  | Size | Depends On  | Status  | Commit |
|-------|---------------------------------------------------------------------------------------|------|-------------|---------|--------|
| T7.1  | Prometheus metrics + Grafana dashboards                                                | M    | T2.x, T3.x  | Pending | -      |
| T7.2  | Runbook in `docs/how-to/`                                                              | S    | T6.x        | Pending | -      |
| T7.3  | Backfill tool (`cashu-ledger trace replay <log-file>`)                                 | M    | T2.x        | Pending | -      |
| T7.4  | Load test harness                                                                     | M    | T3.x        | Pending | -      |

### Cross-Cutting

- Update `pom.xml` parent to add the two new modules.
- Add `cashu-ledger-trace-publisher` to a published Maven coordinate so external backends can consume it.
- Update `docs/README.md` with links to this spec and follow-up Diátaxis tutorials/how-tos.
- Add `CHANGELOG.md` entries per phase.

### Recommended First Implementation Phase

**Phase T1 + the storage portion of T3.1.** Concretely:

1. Define the records and schema (T1.1, T1.2, T1.3) and lock the wire format with byte-level golden tests (T1.4).
2. Stand up a thin `TraceEventStore` over the existing nostrdb infrastructure (T3.1) with at least the `(kind, d-tag)`, `(input_y)`, and `(output_y)` indexes wired up.

With T1 + T3.1 done, every other phase can proceed in parallel: T2 (producer) depends only on T1; T4 (read API) depends only on T3.1; T6 (web UI) depends only on T4. The producer SDK and the read API can be built simultaneously by different contributors as soon as T1 is locked.

## 10. Open Questions

These require stakeholder input before or during implementation. Items closed during the multi-model review are marked **CLOSED** with a reference to the section that resolves them.

1. ~~**Multi-mint support.**~~ **CLOSED** by §5.3.2: per-mint sub-graphs default with opt-in "All mints" overlay; cross-mint flows are modelled as two independent events correlated by a shared `transfer_id` UUIDv7. New `transfer` `edge_role` and a `(tag_name='transfer_id', tag_value, transition_at)` SQLite index support the lookup. Missing counterparts are surfaced as `transferCounterpartMissing: true` rather than treated as errors.
2. ~~**Backfill of historical events.**~~ **CLOSED**: traceability begins on the day the producer SDK is first adopted in a deployment. Best-effort reconstruction from existing nostrdb voucher events is rejected because pre-SDK proofs lack the data needed to derive `Y` (`hash_to_curve(secret)` requires the raw `secret`, which historical voucher events do not carry). Producing partial DAGs with `provenance=backfill_partial` would mislead more than it would inform — operators would interpret missing edges as evidence of activity that never happened, when in fact it is evidence the data was never captured. Operators wanting earlier history MUST archive raw mint/wallet logs separately; cross-correlation with the trace ledger is out of scope.
3. ~~**Federation / cross-instance ledger.**~~ **CLOSED — DEFERRED**: explicitly out of scope per §11 ("Cross-instance federation of ledgers"). The intra-deployment cross-mint case is handled via `transfer_id` (§5.3.2). Cross-operator federation requires a separate spec covering trust model, signed export packages or shared-relay scoping, and privacy contract negotiation; it remains deferred until at least one production deployment requests it.
4. ~~**Voucher and traceability event ordering.**~~ **CLOSED**: producer SDK MUST publish the voucher event (`kind 30078`) FIRST, await its acceptance from at least one ledger-subscribed relay, then publish the trace event (`kind 9079`) referencing the voucher's `d` tag via `voucher_ref`. Rationale: the trace event's `voucher_ref` is a pointer; emitting the trace event first creates a window in which consumers can resolve the pointer to nothing. Each event is independently useful — a trace event without a voucher is meaningful (the operation happened) and a voucher without a trace event is meaningful (legacy compatibility for pre-SDK deployments) — but a trace event whose `voucher_ref` cannot resolve is a worse user experience than a brief delay. The producer SDK enforces the ordering when publishing both events for the same operation; if the voucher publish fails, the trace event is published without `voucher_ref` and the SDK retries the voucher in the background.
5. ~~**Schema evolution.**~~ **CLOSED** by §5.12: four-tier compatibility ladder (silent / silent / warn / reject), additive vs breaking distinction, mandatory CHANGELOG and Revision Log entries on every bump, discovery via `GET /relays`.
6. ~~**Token bundle representation.**~~ **CLOSED** by §5.10 Bundle Token Handling: parsed by default, raw `cashuB` v4 CBOR token optionally carried in `content.bundleToken` (FULL only), verified on ingest against `inputs` (`B1_BUNDLE_MISMATCH`), 64 KB cap, V3 (`cashuA`) refused with `TRACE_LEGACY_TOKEN_FORMAT`. API: `?include=parsed|raw|both` on `/events/{id}`.
7. ~~**Sanitised export for sharing.**~~ **CLOSED** by §7.5: sanitise tool re-redacts a `FULL` export under a freshly generated 256-bit ephemeral HMAC key; the operator's deployment-scoped `redaction_key` never leaves the deployment. Two artefacts (`export.json` + `ephemeral_key.bin`) are delivered via separate channels. Sanitised exports are forced to `HASHED` mode, carry a `sanitised: true` flag, and strip raw `cashuB` token bundles (`bundleToken`) which cannot be partially redacted.
8. ~~**Time-series visualisation.**~~ **CLOSED — DEFERRED** to v2 of the web UI. The graph (node-link) view in §5.8 is the only visualisation in v1. A Sankey diagram aggregating value flow over time has clear utility for accounting and forensic review, but it is duplicative of the graph view's information content and adds a second visualisation pipeline to maintain. Revisit after T7 telemetry lands so the decision can be informed by actual operator usage patterns rather than speculation.
9. ~~**Partial Lightning settlement (MPP).**~~ **CLOSED** by adopting option (b): a `MELT` for the settled portion plus a paired `MELT_REFUND` carrying the unsettled remainder back to the user as fresh proofs. Option (a) — a single `MELT_FAILED` with `partial=true` — was rejected because `MELT_FAILED` semantics mark the proofs as never-spent in the DAG, but a partial MPP settlement DID burn the proofs that paid the settled portion at the mint. Option (b) preserves the accounting truth: the `MELT` records what actually burned, and the `MELT_REFUND` records the new fresh proofs minted to refund the unsettled balance. New `MELT_REFUND` operation kind, `LightningRef.partial=true` flag on both events, `B3_PARTIAL_SETTLEMENT` validation invariant, and a `partial-settlement-window` (default 60s) reconciliation timer are defined in §5.9.
10. ~~**NIP-44 encrypted content for FULL mode over shared relays.**~~ **CLOSED — DEFERRED** to v2 of the spec. When adopted, the ledger MUST use **per-day rotating session keys** (not a long-term decryption key) per §7.2. Rotation bounds the blast radius of a key compromise to one day's events; the prior day's key remains read-only for a default 30-day retention window so ingest stays decryptable for retrospective review. The active key id is published via `GET /relays`; the key material is fetched by producers via a separate authenticated channel that the v2 spec will define.
11. ~~**Pruning policy for terminal subgraphs.**~~ **CLOSED** by §5.11: sub-DAG terminality pruning is supported alongside age-based pruning. A sub-DAG is eligible for pruning when every leaf event has `activity=TERMINAL` per §5.4.1; the activity cache is the data source so no extra graph walk is required. The mode is configurable (`trace.pruning.terminal-subdag.enabled`, default on) and pauses automatically when the activity cache is stale to avoid over-pruning.
12. **Issuer back-fill for issuer-less events.** A producer may publish a `MINT` or `SWAP` before the corresponding voucher is issued, leaving `issuer_id` and `issuer_pubkey` absent. Three strategies are possible: (a) accept that issuer-less events never gain issuer tags and require callers to follow the `voucher_ref` link at read time when a later voucher binds to the same proofs (one extra round-trip per result); (b) emit a `correction_of` event when the voucher arrives, denormalising the issuer into a corrected trace event (doubles event volume for the affected operations); (c) maintain a sidecar mapping of `(keyset_id, Y) → issuer` in the SQLite index and back-fill the index rows on voucher ingest without emitting a Nostr correction (preserves event volume, but the raw signed event no longer matches the indexed query result, which is a subtle audit hazard). Pick one before T2.x. Default lean: (c), with an explicit `issuer_provenance=index_backfill` annotation on responses where the source is the sidecar rather than the raw event.

### Closed during review

- ~~Privacy posture default for shared deployments~~ — **CLOSED** by Section 7.2: producer SDK refuses `FULL` mode unless every relay is annotated `private=true`.
- ~~Replaceable-vs-regular Nostr kind / Nostr event kind selection~~ — **CLOSED** by Section 5.3: kind `9079` (regular, non-replaceable) is selected; `30079` is rejected.
- ~~Producer attestation~~ — **CLOSED** by Section 7.1: ledger requires producer pubkey registration per `mint_url`; rogue publishes are rejected with `TRACE_FORBIDDEN_PRODUCER`.
- ~~Indexing strategy~~ — **CLOSED** by Section 5.4: SQLite sidecar index alongside nostrdb, with rebuild on startup.
- ~~NUT-08 fee-return modelling~~ — **CLOSED** by Sections 5.3 (`output_role`) and 5.9 (validation invariants R1, F1): `feeAmount` is actually-charged; `fee_return` outputs ride in the same MELT event.
- ~~Schema evolution policy~~ — **CLOSED** by Section 5.12: four-tier ladder (`N`/`N-1` silent, `N-2` `TRACE_DEPRECATED_SCHEMA`, `≤N-3` `TRACE_UNSUPPORTED_SCHEMA`, `>N` `TRACE_FUTURE_SCHEMA`); additive changes (new tags, kinds, error codes, `output_role` values) do not bump the version; `GET /relays` advertises `supportedSchemaVersions`.
- ~~Token bundle representation~~ — **CLOSED** by Section 5.10 Bundle Token Handling: optional `content.bundleToken` field on SEND/RECEIVE in FULL mode only; ledger re-parses CBOR on ingest and rejects mismatches as `B1_BUNDLE_MISMATCH`; 64 KB cap; V4-only (`cashuB`); `GET /events/{id}?include=parsed|raw|both` controls the read shape.
- ~~Multi-mint support and cross-mint flows~~ — **CLOSED** by Section 5.3.2: per-mint sub-graphs default in the UI, opt-in "All mints" overlay; cross-mint flows modelled as two events correlated by `transfer_id`; new `transfer` edge role; sidecar index `(tag_name='transfer_id', tag_value, transition_at)`.
- ~~Historical backfill~~ — **CLOSED**: no backfill. Traceability starts at producer SDK adoption. Pre-SDK voucher events lack the raw `secret` required to derive `Y`, and partial DAGs would mislead operators.
- ~~Federation / cross-instance ledger~~ — **CLOSED — DEFERRED** to §11. Intra-deployment cross-mint is handled via `transfer_id` (§5.3.2); cross-operator federation requires its own spec covering trust model, export format, and privacy contract.
- ~~Voucher / traceability event publish ordering~~ — **CLOSED** by §5.7: voucher event (`kind 30078`) is published first and awaits relay ack before the trace event (`kind 9079`) with `voucher_ref` is published. Failed voucher publishes degrade to trace-without-`voucher_ref`; consumers tolerate either the trace alone or the voucher alone, but never a `voucher_ref` that resolves to nothing.
- ~~Sanitised export for external sharing~~ — **CLOSED** by Section 7.5: sanitise tool re-redacts under a freshly generated ephemeral HMAC key; export and key are delivered separately; operator's `redaction_key` never leaves the deployment; `cashuB` bundle tokens stripped during sanitisation; sanitised exports carry `sanitised: true` and `ephemeral_key_id`.
- ~~Time-series / Sankey visualisation~~ — **CLOSED — DEFERRED** to v2 of the web UI. Graph (node-link) view is the only visualisation in v1; revisit after T7 telemetry shows real operator usage patterns.
- ~~Partial Lightning settlement (MPP)~~ — **CLOSED** in favour of option (b): `MELT` for the settled portion plus a paired `MELT_REFUND` for the unsettled remainder. New `MELT_REFUND` operation kind, `LightningRef.partial` flag, `B3_PARTIAL_SETTLEMENT` validation invariant, and a 60s default `partial-settlement-window` reconciliation timer.
- ~~NIP-44 encrypted content for FULL mode over shared relays~~ — **CLOSED — DEFERRED** to v2. When adopted, the ledger uses per-day rotating session keys (not a long-term key) with a 30-day default retention window for retrospective decryption; active key id advertised via `GET /relays`.
- ~~Pruning policy for terminal sub-DAGs~~ — **CLOSED** by Section 5.11: sub-DAG terminality pruning is supported alongside age-based pruning, reading from the §5.4.1 activity cache, gated by `trace.pruning.terminal-subdag.enabled` (default on), and automatically paused when the activity cache is stale.

## 11. Out of Scope (deferred)

- Cross-instance federation of ledgers.
- Anonymity-preserving public chain.
- ML-based anomaly detection (e.g., flagging unusual value flows).
- Mobile apps.
- Replacement of the existing voucher event schema; voucher events remain authoritative.
- Editing or deleting events in place; corrections only via `correction_of`.
- Real-time consensus across multiple ledger instances.

## 12. Glossary

- **Proof.** A Cashu blinded signature unit; defined by NUT-00 with fields `amount`, `id` (keyset id), `secret`, `C`.
- **Y.** The point `hash_to_curve(secret)` per NUT-00; serves as the canonical de-duplication key for a proof.
- **DLEQ.** Discrete-log equality proof from the mint that the blinded signature was produced honestly (NUT-12).
- **Witness.** Optional unlock data for spending conditions (NUT-10/11/14).
- **DAG.** Directed acyclic graph; here, the graph whose nodes are `TransactionEvent`s and whose edges are matched `Y` values.
- **NIP-98.** Nostr HTTP authentication; reused from `cashu-ledger-client-api`.
- **Outbox.** A persistent local queue holding events to be delivered to relays.

## Revision Log

### Round 1 — Gemini Review

- [Section 4.1, FR-4 / FR-5] Specified that the canonical join key is the tuple `(keyset_id, Y)`, not bare `Y`, to defend against future NUT-introduced curve or hash changes that could collide.
- [Section 4.1] Added FR-5a to clarify that quote-only operations are standalone DAG nodes linked to their settlement events via `quote_id`, and that quote events are never replaced or mutated.
- [Section 4.5, FR-18 / FR-20] Resolved the ambiguity between `created_at = transition_at` (deterministic event id) and the `clock-skew-tolerance` (server-side sanity check). FR-18 now requires exact equality for the producer to compute a deterministic id; FR-20 explicitly compares `created_at` to the ledger's local wall-clock at ingest.
- [Section 5.2] Replaced bare SHA-256 redaction with HMAC-SHA-256 under a deployment-scoped `redaction_key`, added a corresponding `redaction_key_id` tag, and documented key-rotation and storage requirements. This closes the secret-confirmation attack Gemini called out.
- [Section 5.3] Added `quote_id`, `redaction_key_id`, and `output_role` tags to the schema. `output_role` covers NUT-08 fee returns and distinguishes target outputs from change in SWAP / MELT operations.
- [Section 10] Added Open Question 14 (replaceable-vs-regular Nostr kind) and Open Question 15 (NUT-08 fee-return modelling and partial Lightning settlement).
- [Section 10] Augmented Open Question 12 with the redaction-key sharing dilemma for sanitised exports.

### Round 1 — Codex Review

- [Section 4.1, FR-1 / FR-1a] Partitioned operation kinds into mint-state-change, possession-only, and quote/failure categories with distinct DAG semantics. SEND/RECEIVE no longer create proof-derived edges; they link via a new `bundle_id` tag. MINT_FAILED/MELT_FAILED produce auxiliary `attempt` edges instead of consuming proofs. Closes Codex #9 and clarifies #10.
- [Section 4.1, FR-5b] Added explicit double-spend visibility rule: conflicting consumers of the same `(keyset_id, Y)` are both shown with a `double_consume=true` annotation. The ledger does not adjudicate.
- [Section 4.2, FR-10] Replaced "first relay OK" delivery contract with "ledger-subscribed relay set" — the producer SDK fetches the ledger's relay set and waits for at least one of those relays to acknowledge, closing the gap that relay acceptance does not imply ledger visibility (Codex #17).
- [Section 4.2, FR-11a / FR-11b] Resolved the outbox-overflow contradiction (Codex #5): default policy is `BLOCK_AND_ALERT` (no silent dropping); alternative `DROP_*` and `FAIL_OPEN` policies are explicit and require an `overflow_policy` tag on every event so consumers know whether gaps are possible.
- [Section 4.5, FR-20] Made the clock-skew check asymmetric (Codex #1): future timestamps rejected at 60 s; past timestamps accepted within the 24 h outbox retention window. Legitimate outbox retries are no longer rejected.
- [Section 5.1] Locked module ownership of DTOs (Codex #12): all schema records live in `cashu-ledger-trace-core`; the producer SDK depends on it instead of mirroring DTOs.
- [Section 5.1] Annotated `ProofRef` fields with their NUT-00 wire JSON keys (`id`, `C`, etc.) per Codex #13.
- [Section 5.3] Decided the Nostr kind: **`9079`** (regular, non-replaceable). The `30079` parameterised-replaceable proposal is rejected because append-only audit semantics conflict with replaceable kinds (Codex #3 / #14 in Round 1's Open Questions).
- [Section 5.3] `d` tag format clarified: canonical 36-character dashed UUIDv7. Removed the conflicting "without dashes" wording (Codex #4).
- [Section 5.3] Composite tag values: `input_y` and `output_y` now carry `<keyset_id>:<y_hex>` so the join key is honoured at the wire level. `quote_id` uses `<mint_url>::<quote_id>` to avoid cross-mint collisions (Codex #2, #18). Added `bundle_id` and `overflow_policy` tags. Removed the duplicated `quote_id` row.
- [Section 5.3] Added a complete canonicalisation rules subsection: explicit tag ordering, RFC 8785 JSON canonicalisation for content, integer `created_at`, `traceability_op == d`. This makes the deterministic event id reproducible across producer implementations (Codex #4).
- [Section 5.4] Decided indexing strategy (Codex #16, Round 1's #10): SQLite sidecar alongside nostrdb, with online rebuild. Closes the open question.
- [Section 5.5 / 5.8] Tightened browser secret handling (Codex #14): explicit user action to reveal payloads, `Cache-Control: no-store`, no localStorage caching, audit log entry per reveal.
- [Section 5.9] New section: per-operation validation invariants (kind-by-kind input/output/Lightning/fee rules) with a uniform `INVALID_OPERATION` error code and per-rule sub-codes (Codex #11).
- [Section 5.10] New section: NUT-00 wire format mapping (Codex #13). `FULL`-mode round-trip preserves byte-identical NUT-00 fields; the `y` field is the only ledger-added key.
- [Section 5.11] New section: pruning and tombstones (Codex #15). `EVENT_PRUNED` deletes the payload but writes a tombstone preserving `(keyset_id, Y)` join keys so walks remain meaningful across pruned hops. Reconciled with Section 11 ("editing or deleting events" remains out of scope; pruning is a ledger-internal retention action).
- [Section 7.1] Added producer attestation as an explicit mitigation, including a `trace.producers` config example. Closes Codex #8 / Round 1 Open Question 9.
- [Section 7.2] Made `FULL` mode conditional on a private relay set; added `PRIVATE_RELAY_REQUIRED` startup error. Reserved NIP-44 encrypted content as future work. Closes Codex #7.
- [Section 7.5] Corrected SHA-256 vs. HMAC-SHA-256 inconsistency (Codex #6). SHA-256 is now reserved for the Nostr event id only; all secret-bearing redaction uses HMAC.
- [Section 10] Closed five open questions outright (privacy posture, Nostr kind, producer attestation, indexing strategy, NUT-08 fee-return modelling) and renumbered the remaining ones. Added a "Closed during review" subsection so reviewers can see what was resolved.

### Round 2 — Gemini Review

- [Section 4.5, FR-18] `transition_at` upgraded to millisecond precision so that high-throughput producers do not produce ties. The Nostr `created_at` field is computed as `floor(transition_at_ms / 1000)` to preserve NIP-01 compatibility while keeping deterministic event ids.
- [Section 5.3] Renamed `actor` → `producer_pubkey` and added an optional `initiator_pubkey` for end-user identity. The producer attests to the initiator without requiring the user to sign Nostr events. Updated canonicalisation tag ordering, the example event, the Java record, the `/events` filter parameters, and Section 7.1 producer attestation rules to match.
- [Section 5.3] `unit` tag now mandates lowercase ASCII, with a recommended registry (`sat`, `msat`, `usd`, ...). Plural forms (`sats`) are forbidden.
- [Section 5.5] Removed offset-based pagination across all list endpoints; replaced with cursor-based pagination keyed on `(transition_at, event_id)`. Closes Gemini #3.
- [Section 5.5] `/proofs/{y}` redefined to return the **chronological list** of events involving the proof (origin → terminal), not just the latest event. Includes role per event and aggregated `originEventId` / `terminalEventId` / `currentlySpent`. Closes Gemini #10.
- [Section 5.5] Added `/quotes/{quoteId}/status` endpoint synthesising a quote's current lifecycle (pending / succeeded / failed / expired) without requiring callers to walk the DAG. Closes Gemini #6.
- [Section 5.5] Added `/relays` discovery endpoint to support FR-10's ledger relay set negotiation.
- [Section 5.9] Restated `RESTORE` semantics: restored proofs are graph roots and participate in the DAG identically to minted proofs. The earlier confusing "no edges unless later spent" wording is corrected. Closes Gemini #8.
- [Section 5.11] Pruning now **preserves** the SQLite sidecar index rows for join keys (rather than deleting them) and updates them to point at the tombstone. This is what actually makes the "preserve graph topology" promise work. Closes Gemini #4.
- [Section 5.2] Added a Redaction Key Registry with admin-only endpoints to register, list, and verify candidate plaintext against historical redaction keys. Auditors holding `trace:read:hashed` can confirm specific values without holding raw keys. Closes Gemini #5.
- [Section 6.3] Replaced the static 250 ms / batch-64 dispatcher with an adaptive scheme: high-water mark switches to 50 ms / batch-256, sustaining ≥ 5 000 events/s well beyond the NFR-3 burst goal. Closes Gemini #9.

### Round 2 — Codex Review

- [Section 5.3 / FR-18] Fixed the `created_at` canonicalisation rule: `created_at = floor(transition_at_ms / 1000)`, not strict equality. Added FR-18a clarifying that `transition_at` is serialised as a decimal string (Nostr tag values are strings).
- [Section 9 / Roadmap] Replaced remaining `30079` references with `9079` in tasks T1.2 and T3.1.
- [NFR-1, FR-11a, Section 6.4] Reconciled the apparent contradiction between fire-and-forget publish and `BLOCK_AND_ALERT` overflow: NFR-1's < 5 ms p99 budget now explicitly applies under nominal conditions only; the blocking case is the deliberate, observable signal that the pipeline is overloaded. The Section 6.4 failure table now references the active overflow policy rather than claiming events are dropped on overflow.
- [Sections 5.1, 5.7, 6.2] Removed the residual DTO-mirroring contradiction. `cashu-ledger-trace-core` is the sole DTO source; the producer SDK depends on it; module layout text in 6.2 was rewritten to reflect this.
- [Section 5.7] Fixed signing key terminology: Nostr is secp256k1 Schnorr, not Ed25519.
- [Section 5.9 / H1] Corrected the cross-cutting hex rule: `secret` and `witness` are opaque per NUT-00 / NUT-10–11 and are not constrained to hex. Only fields that are actually hex are enforced as lowercase hex.
- [Sections 5.1, 5.2] Resolved `bolt11` HASHED-mode conflict. `LightningRef.bolt11` is now `raw in FULL, HMAC'd in HASHED, omitted in MINIMAL` to match the privacy table.
- [Sections 5.1, 5.5] Extended `LightningRef` with `mintUrl`, `amount`, `expiresAt`, and `quoteOperation` so the new `/quotes/{id}/status` endpoint and MELT validation rule B2 have the data they need.
- [Section US-2 / 5.5] Specified that proof secrets are NEVER sent to the API. The CLI / browser computes `Y = hash_to_curve(secret)` locally; the API accepts only `Y` (and optional `keysetId`).
- [Section 5.9 / RECEIVE] Made RECEIVE validation tolerant of out-of-order arrival: ledger accepts RECEIVE without a known SEND, marks `unmatched_send=true`, reconciles within a 7-day window, and surfaces persistent dangling RECEIVEs in `/stats`.
- [Section 5.11] Reworked pruning semantics so it does not pretend to mutate a signed Nostr event: pruning deletes the entire raw event from nostrdb and stores a separate, unsigned **tombstone record** (in a SQLite tombstones table, not in nostrdb). The original `d` value is reserved (cannot be reused), and walks render tombstones as a distinct node type with `edgeRole=tombstone`.
- [Section 6.3 / 5.4] Replaced the unrealistic "atomic transaction across nostrdb + SQLite" with a write-ahead protocol: nostrdb is the system of record; sidecar writes are idempotent (`INSERT OR IGNORE`); a reconciler retries failed sidecar writes and rebuilds from nostrdb on startup; an index-lag metric and `503 INDEX_LAGGED` response surface lag.
- [Section 5.4] Renamed the obsolete `actor` sidecar index to explicit `producer_pubkey` and `initiator_pubkey` indexes; added matching `findByProducer` / `findByInitiator` methods to `TraceEventStore`.
- [Section 5.5] Walk and visualisation responses now include explicit edge schema with `edgeRole` (`spend` / `quote` / `possession` / `attempt` / `tombstone`), `keysetId`, `amount`, `outputRole`, `bundleId`, `quoteId`, and `doubleConsume` / `conflictingConsumers` fields.
- [NFR-4a] Added hard event-size limits with rejection codes: 64 KB total event, 64 inputs/outputs each, 8 KB secret, 16 KB witness, 4 KB bolt11, 1 KB error message. Tests in Section 8 will cover oversized rejections.

### Round 3 — Gemini Review

- [Section 5.4] Index rebuild now explicitly merges nostrdb events with the tombstones table so post-prune rebuilds preserve proof-derived index rows for pruned hops. Without this, a sidecar rebuild after a pruning cycle would lose the join keys needed to traverse pruned segments.
- [Section 5.4] Added an auxiliary index column `(quote_expires_at, mint_url)` so `/quotes/{id}/status` can compute the `expired` state by scanning the open-quote subset rather than parsing every pending event's `content` JSON.
- [Section 5.4] Sidecar `input_y` / `output_y` indexes now include `mint_url` as part of the composite key. This guarantees absolute multi-tenant isolation in case two mints' 8-byte keyset ids ever collide.
- [Section 5.5] Clarified that `/quotes/{quoteId}/events` and `/quotes/{quoteId}/status` accept the raw producer-supplied `quote_id` in the path and require `?mintUrl=<url>` as a query parameter; the ledger composes the composite `<mint_url>::<quote_id>` index key internally. This eliminates ambiguity between path-form and tag-form.
- [Section 5.11] Added an explicit "Storage recovery is partial" note: pruning reclaims the bulky nostrdb payload but retains tombstone + sidecar rows indefinitely so the DAG stays traversable. For a 64-in/64-out SWAP the residual sidecar metadata is ~32 KB even after the payload is gone, so operators must size sidecar storage with this in mind.
- [FR-11a] Added operator guidance on policy selection: the default `BLOCK_AND_ALERT` favours audit completeness; high-volume producers (> 1 000 ops/s where blocking is unacceptable) should evaluate `DROP_NEW_AND_ALERT` or `FAIL_OPEN` deliberately and document the trade-off.

### Round 4 — Issuer (merchant) filter patch

Driven by a stakeholder request to filter trace events by the signing merchant. Existing voucher events already carry merchant identity (`VoucherNode.issuerId`, `VoucherNode.issuerPublicKey`; tag `issuer_id` per `VoucherEventMapper`), so the trace schema is extended additively to denormalise this onto every trace event.

- [FR-13] Added "list events that reference a given issuer (merchant) id or issuer pubkey" to the read-API requirements list.
- [Section 5.1] Added `Optional<String> issuerId` and `Optional<String> issuerPubkey` to the `TransactionEvent` record, copied from the voucher at publish time.
- [Section 5.2] Added rows for `issuer_id` and `issuer_pubkey` to the privacy-mode table; both are NEVER hashed/omitted regardless of mode, with rationale (the voucher event already publishes them in plaintext on the same relay; redacting on the trace event would not increase privacy and would break filter UX).
- [Section 5.3] Added `issuer_id` and `issuer_pubkey` Nostr tags with cardinality 0..1; inserted into the canonical tag-ordering rule between `voucher_ref` and `quote_id`; updated the example event with sample values.
- [Section 5.3.1 — new] Added an "Issuer Denormalisation (rationale)" subsection covering the filter-performance argument, the issuer-without-voucher cases (MINT before voucher issuance, bare wallet swaps), the producer-side consistency requirement, and the privacy posture (issuer fields exempt from redaction).
- [Section 5.4] Added `findByIssuerId` and `findByIssuerPubkey` methods to the `TraceEventStore` interface and the corresponding `(tag_name='issuer_id', tag_value, transition_at)` and `(tag_name='issuer_pubkey', tag_value, transition_at)` SQLite indexes. Recommends `issuer_pubkey` over `issuer_id` for cross-deployment merchant aggregation since `issuer_id` is operator-supplied and may collide.
- [Section 5.5] Added `issuerId` and `issuerPubkey` to the `/events` filter list, added a `GET /issuers/{issuerId}/events` convenience endpoint, and added the new path to the cursor-pagination rule. Updated the single-event response shape to surface `issuerId` and `issuerPubkey`.
- [Section 5.6] Added `cashu-ledger trace issuer <issuer-id> [--by-pubkey] [--since] [--until] [--op]` CLI subcommand.
- [Section 5.8] Added "issuer" to the web visualisation anchor type selector (auto-detects id vs pubkey by length and charset) and added an explicit issuer filter input to the left-rail filter set.
- [Section 7.2] Added an explicit clause stating that issuer fields are returned verbatim to all read authorities, with the recommendation to gate exposure at the authority level rather than via field-level redaction.
- [Section 10] Added Open Question 12 — issuer back-fill strategy for events published before a voucher binds (proposed default: sidecar back-fill with `issuer_provenance=index_backfill` annotation, T2.x decision).

### Round 5 — Activity (active vs terminal) filter patch

Driven by a stakeholder request to suppress events whose underlying tokens are no longer live (voucher in `REVOKED` / `REDEEMED` / `EXPIRED` / `RECLAIMED` / `SPLIT`, or all output proofs already spent at the mint). The existing voucher domain already has `VoucherStatus.isTerminal()` and the trace ledger already tracks proof-spent state via the `currentlySpent` field on `/proofs/{y}` — this patch ties the two together into a single event-level filter.

- [FR-13] Added an "activity" filter bullet to the read-API requirements list.
- [FR-13a — new] Defined event activity semantics: `TERMINAL` if the linked voucher is in a terminal status, the event is itself a terminal-kind operation (`MELT`, `MELT_FAILED`, `MINT_FAILED`, `EVENT_PRUNED`), or every output proof has been consumed by a downstream mint-state-change event; `ACTIVE` otherwise. Quote-only events transition on settlement / expiry per the existing `/quotes/{id}/status` rule.
- [Section 5.1] Added `EventActivity { ACTIVE, TERMINAL }` enum to the trace-core domain.
- [Section 5.4] Added `getActivity(eventId)` and `findFiltered(TraceEventQuery)` to the `TraceEventStore` interface; added an `(activity, transition_at)` index column on the events index table.
- [Section 5.4.1 — new] "Activity Classification (cache and invalidation)" subsection covering: the three cached columns (`activity`, `activity_reason`, `activity_changed_at`); the four invalidation triggers (downstream-spend, voucher-state, quote-settlement / expiry, terminal-kind ingest); the eventual-consistency window and `activity_cache_lag_seconds` metric; and the rebuild cost.
- [Section 5.5] Added an `activity` filter parameter (`active` / `terminal` / `any`, default `any`) to `/events`, `/vouchers/{voucherId}/events`, and `/issuers/{issuerId}/events`. Extended the single-event response shape with `activity`, `activityReason`, and `activityChangedAt` fields.
- [Section 5.6] Added `--activity active|terminal|any` to `trace events`, `trace voucher`, and `trace issuer` CLI subcommands.
- [Section 5.8] Added an "activity toggle" to the web UI filter rail (`Active only` / `Terminal only` / `All`); terminal nodes are rendered with a desaturated palette and a badge indicating the `activityReason`.
- [Section 6.1] Updated the architecture diagram to show the new `VoucherStateWatcher` feeding the activity cache invalidator.
- [Section 6.2] Listed the watcher as a `cashu-ledger-core` extension.
- [Section 6.4] Added a "Voucher-state watcher offline" row to the failure-mode table — degrades only the `voucher_terminal` reason; other invalidation triggers keep working; `/stats` surfaces `voucher_watcher_lag_seconds` and responses gain a `Stale-Activity` warning header during the outage.
- [Section 6.5 — new] "Voucher State Watcher" section specifying the kind-`30078` subscription, the in-memory voucher-status map, the high-watermark cursor, the Prometheus metrics, and the failure-isolation contract (watcher failures MUST NOT break trace ingest or reads).

### Round 6 — Schema evolution policy patch

Closes Open Question 5. Driven by the need to lock the producer/ledger compatibility contract before T2.x ships a versioned producer SDK to multiple deployments.

- [Section 5.5] `/relays` table entry now also advertises `supported_schema_versions`; added a `Response shape — GET /relays` JSON example with `supportedSchemaVersions`, `currentSchemaVersion`, and `deprecatedSchemaVersions`.
- [Section 5.9] Rewrote invariant `V1` to delegate to §5.12 instead of hard-coding `schema_version=1` as the only accepted value.
- [Section 5.12 — new] "Schema Evolution Policy" subsection: four-tier compatibility ladder (`N`/`N-1` silent, `N-2` `TRACE_DEPRECATED_SCHEMA` warn, `≤N-3` `TRACE_UNSUPPORTED_SCHEMA` reject, `>N` `TRACE_FUTURE_SCHEMA` reject); producer SDK guard with the `+1` rollout window; the additive-vs-breaking distinction (new tags, kinds, error codes, `output_role` values do NOT bump the version); mandatory CHANGELOG and Revision Log entries on every bump; sidecar re-derivation only when the bump touches indexed columns; new error codes `TRACE_DEPRECATED_SCHEMA`, `TRACE_UNSUPPORTED_SCHEMA`, `TRACE_FUTURE_SCHEMA`, and `TRACE_UNKNOWN_KIND` for older ledgers receiving newer kinds; SDK discovery refresh policy with a 1-hour cache ceiling.
- [Section 10] Open Question 5 marked **CLOSED** in-place and added to the "Closed during review" subsection.

### Round 7 — Bundle token handling patch

Closes Open Question 6. Driven by the audit requirement that operators be able to verify what `cashuB` token actually shipped during a `SEND`/`RECEIVE`, byte-for-byte, rather than trust the parsed proof list alone.

- [Section 5.5] Extended `GET /events/{eventId}` with the `?include=parsed|raw|both` query parameter; added `bundleTokenRaw: null` to the single-event response shape.
- [Section 5.6] Added `--raw` flag to `cashu-ledger trace event`, gated on `trace:read:full` and `FULL` privacy mode.
- [Section 5.8] Added a "Show raw token" tab on the SEND/RECEIVE drill-down panel with the same eviction / banner / audit-log rules as the parsed reveal.
- [Section 5.9] Extended SEND and RECEIVE invariant rows with the `B1_BUNDLE_MISMATCH` rule: if `bundleToken` is present, the ledger re-parses and asserts equality with the event's `inputs`.
- [Section 5.10 — new "Bundle Token Handling (V4)" subsection] Optional `content.bundleToken` field on SEND/RECEIVE; FULL-mode-only; V4 (`cashuB`) only with V3 (`cashuA`) refused via `TRACE_LEGACY_TOKEN_FORMAT`; ledger-side ingest verification via `cashu-java`'s existing v4 CBOR parser; 64 KB cap (overridable via `trace.bundle-token.max-bytes`) with `TRACE_BUNDLE_TOO_LARGE` rejection; defensive parser limits (max depth 8, reject unknown CBOR major types); tombstone behaviour (raw token lost on prune, `bundle_id` link survives).
- [Section 10] Open Question 6 marked **CLOSED** in-place and added to the "Closed during review" subsection.

### Round 8 — Multi-mint and cross-mint flow patch

Closes Open Question 1. Driven by deployments running multiple mints and the need to correlate value flowing between them without violating the "one mint per event" invariant.

- [Section 5.3] Added `transfer_id` Nostr tag (cardinality 0..1, UUIDv7) to the tag table; inserted into the canonical tag-ordering rule between `bundle_id` and `voucher_ref`.
- [Section 5.3.2 — new] "Multi-Mint and Cross-Mint Flows" subsection: per-mint sub-graph as the visualisation default, opt-in "All mints" overlay; the two-event model for cross-mint flows (each mint emits its own event sharing a `transfer_id`); the rationale for rejecting a single-event model (would violate `M1` mint_url consistency); failure-mode handling (missing counterpart surfaced as `transferCounterpartMissing: true`, not an error).
- [Section 5.4] Added `(tag_name='transfer_id', tag_value, transition_at)` to the SQLite sidecar index list.
- [Section 5.5] Added `transferId` filter to `/events`; extended the `edgeRole` table with a `transfer` value drawn between two events on different mints sharing a `transfer_id`.
- [Section 5.8] Added a "multi-mint view selector" to the web-UI filter rail (`Per-mint` default, `All mints overlay`); per-mint colours derive deterministically from the `mint_url` hash so overlays are visually consistent.
- [Section 10] Open Question 1 marked **CLOSED** in-place and added to the "Closed during review" subsection.

### Round 9 — Historical-backfill closure

Closes Open Question 2. Confirms that traceability starts at SDK adoption rather than reconstructing pre-SDK history from nostrdb voucher events; the raw `secret` required to compute `Y` is not present in historical voucher events and a partial DAG would be misleading.

- [Section 10] Open Question 2 marked **CLOSED** in-place and added to the "Closed during review" subsection.

### Round 10 — Federation deferral

Closes Open Question 3 by formally deferring cross-operator federation to a future spec. The intra-deployment cross-mint case is already covered by `transfer_id` (§5.3.2); operator-to-operator federation requires its own trust model, export format, and privacy contract negotiation that this spec does not attempt.

- [Section 10] Open Question 3 marked **CLOSED — DEFERRED** in-place; cross-referenced to §11.

### Round 11 — Voucher / trace event publish ordering

Closes Open Question 4 by mandating that the voucher event is always published before the trace event when the SDK emits both for one operation. The trace event then references the voucher via `voucher_ref` knowing the pointer will resolve. The reverse — trace first — was rejected because a failed voucher publish would leave a `voucher_ref` pointing at nothing, a worse consumer experience than the brief publish-ordering delay.

- [Section 5.7] Added a "Voucher / trace event publish ordering" producer SDK rule.
- [Section 10] Open Question 4 marked **CLOSED** in-place and added to the "Closed during review" subsection.

### Round 12 — Sanitised export tool

Closes Open Question 7 by specifying a sanitise tool that produces self-contained export packages re-redacted under a one-shot ephemeral HMAC key. The operator's deployment-scoped `redaction_key` never leaves the deployment, and the auditor's view is bounded to the export they were given.

- [Section 5.6] Added `cashu-ledger trace export <anchor> --sanitise --output <dir>` CLI subcommand.
- [Section 7.5 — new] "Sanitised Export for External Sharing" subsection defining the workflow (anchor selection → ephemeral key generation → re-redaction → two-artefact delivery), the auditor's verification scope (joins within the export only), the privacy contract (production `redaction_key` refused; `bundleToken` stripped because partial CBOR redaction is not safe; `sanitised: true` and `ephemeral_key_id` markers; `FULL` round-trip not preserved), and audit logging requirements.
- [Section 7.6] Renumbered the previous "Cryptographic Considerations" subsection from 7.5 to 7.6 to make room.
- [Section 10] Open Question 7 marked **CLOSED** in-place and added to the "Closed during review" subsection.

### Round 13 — Time-series visualisation deferral

Closes Open Question 8 by deferring Sankey-style flow visualisation to a future v2 of the web UI. Graph (node-link) view in §5.8 remains the only visualisation in v1; a Sankey adds a second pipeline to maintain and is largely duplicative of the graph view's information content. Re-evaluation deferred until T7 telemetry shows real operator usage patterns.

- [Section 10] Open Question 8 marked **CLOSED — DEFERRED** in-place.

### Round 14 — Partial Lightning settlement (MPP) modelling

Closes Open Question 9 by adopting the two-event model for partial Lightning settlement: a `MELT` recording the proofs that actually burned at the mint plus a paired `MELT_REFUND` carrying the unsettled remainder back to the user as freshly minted proofs. The single-`MELT_FAILED`-with-`partial=true` alternative was rejected because `MELT_FAILED` semantics mark proofs as never-spent, which contradicts the on-mint reality during a partial settlement.

- [Section 4.1, FR-1] Added `MELT_REFUND` to the recognised operation kinds list with a brief description of partial-settlement semantics.
- [Section 4.1, FR-1a] Added `MELT_REFUND` to the mint-state-change kinds and noted that it has empty inputs and ≥1 outputs (graph-root behaviour like MINT and RESTORE).
- [Section 4.1, FR-5] Added `MELT_REFUND` to the producer-events list (its outputs begin proof-derived edges).
- [Section 5.1] Added `MELT_REFUND` to the `OperationKind` enum and a `boolean partial` field to `LightningRef` (true on both `MELT` and `MELT_REFUND` when settlement was partial).
- [Section 5.9] Added a `MELT_REFUND` validation row: empty inputs, ≥1 outputs, required `quote_id` matching the paired `MELT`, required `LightningRef.partial=true`, a `B3_PARTIAL_SETTLEMENT` balance invariant, and a 60s default `partial-settlement-window` reconciliation timer with `dangling_partial_melt` flagging in `/stats` for windows exceeded.
- [Section 10] Open Question 9 marked **CLOSED** in-place and added to the "Closed during review" subsection.

### Round 15 — NIP-44 encrypted content key-management deferral

Closes Open Question 10 by deferring NIP-44 encrypted content adoption to a future spec revision but locking in the key-management posture: per-day rotating session keys, never a single long-term decryption key, with a 30-day default retention window for retrospective decryption.

- [Section 7.2] Extended the existing NIP-44 reservation note with the per-day rotation requirement, midnight-UTC rotation cadence, retention window, and the `GET /relays` advertisement of the active key id.
- [Section 10] Open Question 10 marked **CLOSED — DEFERRED** in-place and added to the "Closed during review" subsection.

### Round 16 — Sub-DAG terminality pruning

Closes Open Question 11 by supporting sub-DAG terminality pruning alongside age-based pruning. Reuses the §5.4.1 activity cache as the eligibility source so no additional graph walk is required at prune time, gated behind a configurable on-by-default switch, and self-pauses when the activity cache is stale to avoid over-pruning sub-DAGs that look terminal only because the cache hasn't seen a recent state change.

- [Section 5.11] Added a "Sub-DAG terminality pruning" rule and a paired "Activity-cache dependency" rule covering the pause-when-stale safeguard and the corresponding WARN log line.
- [Section 10] Open Question 11 marked **CLOSED** in-place and added to the "Closed during review" subsection.
