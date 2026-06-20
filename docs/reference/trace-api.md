# Transaction-traceability API and CLI reference

Technical reference for the trace read API and the `cashu-ledger trace` CLI. The machine-readable
OpenAPI spec is served at `/v3/api-docs` (UI at `/swagger-ui.html`).

## Authentication

All endpoints under `/api/v1/trace` require a NIP-98 (kind 27235) `Authorization: Nostr <base64>`
header whose `u`/`method` tags match the request. The caller's authority determines the response
shape: `trace:read:summary` (no secrets), `trace:read:hashed`, `trace:read:full`, `trace:admin`.
Responses carry `Cache-Control: no-store`.

## REST endpoints

| Method & path | Purpose |
|---------------|---------|
| `GET /api/v1/trace/events` | List events; filters: `mintUrl`, `producerPubkey`, `initiatorPubkey`, `issuerId`, `issuerPubkey`, `voucherRef`, `quoteId`, `transferId`, `op`, `activity`, `since`, `until`, `limit`, `cursor` |
| `GET /api/v1/trace/events/{eventId}` | A single event (access-shaped) |
| `GET /api/v1/trace/operations/{operationId}` | Event by producer operation id |
| `GET /api/v1/trace/proofs/{y}` | Proof history grouped by `(mintUrl, keysetId)`; optional `mintUrl`/`keysetId` |
| `GET /api/v1/trace/proofs/{y}/walk` | Chain-of-custody walk; `direction` (`up`/`down`/`both`), `depth`, `limit`; `409 AMBIGUOUS_PROOF` for an under-specified multi-mint `y` |
| `GET /api/v1/trace/vouchers/{id}/events` | Events bound to a voucher; `activity`, `limit`, `cursor` |
| `GET /api/v1/trace/issuers/{id}/events` | Events by issuer; `byPubkey`, `activity`, `limit`, `cursor` |
| `GET /api/v1/trace/visualisation` | Per-mint graph for an anchor (`eventId` or `y`+`mintUrl`/`keysetId`); `direction`, `depth`, `limit` |
| `GET /api/v1/trace/stats` | Index health, schema version, ingest counters |
| `GET /api/v1/trace/relays` | Relay set and schema-version bands (`supportedSchemaVersions`, `deprecatedSchemaVersions`) |
| `GET /api/v1/trace/stream` | Server-sent live ingestion notifications |
| `GET /api/v1/trace/admin/redaction-keys` · `POST` · `POST …/{id}/verify` | Redaction-key registry (admin) |
| `GET /api/v1/trace/admin/access-log` · `GET …/index-status` | Audit log and index health (admin) |

Voucher inspect (`GET /api/v1/vouchers/{id}`, not trace-gated) additionally returns a
`transactionEvents[]` array of secret-free summaries.

## CLI commands

`cashu-ledger trace` talks to the read API over HTTP. Shared options: `--api <baseUrl>`
(default `http://localhost:8080/api/v1`), `--key <hex>` (operator key for NIP-98),
`-o/--output text|json|tree`.

| Command | Purpose |
|---------|---------|
| `trace proof --y <Y> [--mint --keyset] [--walk --direction --depth]` | Proof history or chain of custody by `Y` |
| `trace proof --secret <s> …` | As above, deriving `Y` locally via `hash_to_curve` (the raw secret never leaves the machine) |
| `trace voucher <id> [--activity]` | Events bound to a voucher |
| `trace issuer <id> [--by-pubkey] [--activity]` | Events by issuer |
| `trace replay <log-file> --key <hex>` | Re-sign a JSON operation log as deterministic backfill events (idempotent) |
| `trace export [--mint] [--limit] [--sanitise]` | Export events; `--sanitise` re-keys secrets under an ephemeral key for sharing |

`trace proof --walk --output tree` renders the chain as an ASCII DAG.

## Related

- [Operate the trace ledger](../how-to/operate-trace-ledger.md)
- [Transaction Traceability Specification](../design/transaction-traceability-specification.md)
