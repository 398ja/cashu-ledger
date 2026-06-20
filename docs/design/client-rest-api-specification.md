# Client REST API Specification

## Overview

The **cashu-ledger-client-api** module provides an authenticated REST API that allows clients (voucher issuers and recipients) to query and manage their own records in the ledger. Unlike the existing `cashu-ledger-web` module — which serves as a public, unauthenticated inspection tool — this module scopes all queries to the authenticated client's identity and offers aggregated views, analytics, and lifecycle management operations.

### Purpose

- **Portfolio management**: Give clients a consolidated view of all their vouchers (issued and received)
- **Analytics**: Provide aggregated statistics on issuance, claim rates, and value flows
- **Lifecycle tracking**: Monitor voucher state transitions and expiring vouchers in real time
- **Audit**: Enable clients to verify integrity and export records for compliance
- **Recovery**: Allow issuers/senders to identify and reclaim unclaimed vouchers

### Scope

This module is **read-heavy** with limited write operations (reclaim, revoke). It does not handle voucher creation, minting, or proof swaps — those remain the responsibility of the wallet/client application.

---

## Authentication

### NIP-98 HTTP Auth

Authentication uses [NIP-98](https://github.com/nostr-protocol/nips/blob/master/98.md), a Nostr-native HTTP authentication scheme. The client signs a Nostr event authorizing the specific HTTP request, and the server verifies the signature against the client's public key.

#### Request Flow

```
1. Client constructs a kind 27235 Nostr event:
   - "u" tag: the absolute URL of the request
   - "method" tag: the HTTP method (GET, POST, etc.)
   - "payload" tag: SHA-256 hash of the request body (for POST/PUT)
   - created_at: current Unix timestamp

2. Client signs the event with their private key

3. Client includes the base64-encoded signed event in the Authorization header:
   Authorization: Nostr <base64-encoded-event>

4. Server decodes and verifies:
   - Signature validity (Schnorr)
   - URL matches the requested endpoint
   - Method matches the HTTP method
   - Timestamp is within acceptable window (±60 seconds)
   - Event kind is 27235
   - Event ID has not been seen before (replay protection)
```

#### Replay Attack Protection

Within the 60-second timestamp window, an attacker who intercepts a valid `Authorization` header could replay it. To prevent this, the server maintains an **Event ID nonce cache** — an in-memory set of NIP-98 event IDs that have been successfully verified.

**Behavior:**
- After successful signature verification, the server checks if the event's `id` (the SHA-256 hash of the serialized event) has been seen before.
- If the ID exists in the cache, the request is rejected with `401 AUTH_REPLAYED`.
- If the ID is new, it is added to the cache with a TTL equal to `timestamp-tolerance` (default: 60 seconds). Since events older than the tolerance are already rejected by timestamp validation, IDs only need to be retained for the tolerance window.
- The cache is bounded by the `max-registered-clients` setting multiplied by a reasonable requests-per-window estimate, preventing unbounded memory growth.

**Implementation:**
- Uses a `ConcurrentHashMap<String, Instant>` with lazy eviction of expired entries.
- Entries expire after `timestamp-tolerance` seconds (same window as the timestamp check).
- A background cleanup task runs every 30 seconds to purge expired entries.

#### Example

```
Authorization: Nostr eyJpZCI6IjRiNjIyMjY5...
```

The decoded event:

```json
{
  "kind": 27235,
  "created_at": 1742025600,
  "tags": [
    ["u", "https://ledger.example.com/api/v1/client/summary"],
    ["method", "GET"]
  ],
  "content": "",
  "pubkey": "a1b2c3d4e5f6...",
  "sig": "..."
}
```

#### Identity Resolution

The `pubkey` field from the verified NIP-98 event becomes the **client identity** for the request. All queries are automatically scoped to vouchers where:
- The client is the **issuer** (`issuerPublicKey == pubkey`), OR
- The client is the **recipient** (`claimedBy == pubkey`), OR
- The client is the **sender** (original creator who sent the voucher)

### Error Responses

| Scenario | HTTP Status | Error Code |
|----------|-------------|------------|
| Missing Authorization header | 401 | `AUTH_REQUIRED` |
| Malformed or invalid base64 | 401 | `AUTH_INVALID` |
| Invalid signature | 401 | `AUTH_SIGNATURE_INVALID` |
| Expired timestamp (>60s drift) | 401 | `AUTH_EXPIRED` |
| URL mismatch | 401 | `AUTH_URL_MISMATCH` |
| Method mismatch | 401 | `AUTH_METHOD_MISMATCH` |
| Replayed event ID | 401 | `AUTH_REPLAYED` |

---

## Sender Identity

### The "Sender" Role

The Cashu ledger tracks three roles: **Issuer** (event author), **Recipient** (`claimed_by`), and **Sender** (the party who transmitted the voucher to the recipient). In many cases the issuer and sender are the same party, but after a split the sender of a child voucher may differ from the original issuer.

### Nostr Event Tag

The sender is tracked via a dedicated `sent_by` tag on voucher events:

```json
{
  "kind": 30078,
  "tags": [
    ["d", "voucher:v-1766748473969"],
    ["status", "issued"],
    ["sent_by", "<sender_pubkey_hex>"]
  ]
}
```

**Rules:**
- The `sent_by` tag is set when a voucher is created in `ISSUED` state as the result of a split or direct send.
- If the `sent_by` tag is absent, the event's `pubkey` (author/issuer) is assumed to be the sender. This provides backward compatibility with events that predate the tag.
- The `sent_by` value is a 64-character hex Nostr public key.
- The sender identity is immutable — it is set at issuance and never changes across state transitions.

### Impact on Access Control

The sender role is critical for:
- **Reclaim operations**: Only the sender (not just the issuer) can reclaim an unclaimed voucher, because the sender holds the original token proofs.
- **List/search scoping**: The sync engine also queries for events with `#sent_by` matching the client's pubkey, in addition to `authors` and `#claimed_by` queries.

### Sync Engine Query

```
Filter { kinds: [30078], "#sent_by": [clientPubkey], since: lastSyncTimestamp }
```

This is a third relay subscription per client (in addition to `authors` and `#claimed_by`), required to discover vouchers where the client is the sender but not the author (e.g., split-and-forward scenarios).

---

## Module Structure

### New Maven Module

```
cashu-ledger/
├── cashu-ledger-core/
├── cashu-ledger-cli/
├── cashu-ledger-web/              # Existing public inspection API
├── cashu-ledger-client-api/       # NEW: Authenticated client API
│   ├── src/
│   │   ├── main/java/xyz/tcheeric/cashu/ledger/client/
│   │   │   ├── auth/              # NIP-98 authentication filter
│   │   │   ├── controller/        # REST controllers
│   │   │   ├── dto/               # Request/response DTOs
│   │   │   ├── service/           # Client-scoped business logic (reads from nostrdb)
│   │   │   ├── sync/              # Background sync engine (writes to nostrdb from relays)
│   │   │   └── config/            # Spring configuration
│   │   ├── main/resources/
│   │   │   └── application.yml
│   │   └── test/java/
│   └── pom.xml
├── cashu-ledger-integration-tests/
└── cashu-ledger-e2e-tests/
```

### Dependencies

| Dependency | Purpose |
|------------|---------|
| `cashu-ledger-core` | Domain models, services, relay connectivity, nostrdb storage |
| `nostr-java-crypto` | Schnorr signature verification for NIP-98 |
| `spring-boot-starter-web` | REST framework |
| `spring-boot-starter-security` | Security filter chain |
| `spring-boot-starter-scheduling` | Background sync scheduling |

---

## Storage Architecture

### Design Principle: Storage-First

The client API follows a **storage-first** architecture. Unlike the public web module — which queries relays on demand and treats nostrdb as an optional cache — the client API **requires** nostrdb as its primary data source and uses relay queries only for background synchronization.

This is critical for performance: the client API serves aggregation, analytics, and filtered queries that would be unusably slow if every request triggered relay round-trips.

```
┌─────────────────────────────────────────────────────────────────┐
│                     Client API Request                           │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│              ClientVoucherService                                │
│  - Queries nostrdb directly (no relay calls)                    │
│  - In-memory computed cache for summaries/analytics (TTL)       │
│  - Maps StoredEvents → VoucherNode → DTOs                       │
└───────────────────────────┬─────────────────────────────────────┘
                            │ reads
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│              NostrDbEventStore (REQUIRED)                        │
│  - LMDB-backed persistent storage                               │
│  - Primary query source for all client endpoints                │
│  - Indexed by: event ID, d-tag, author pubkey, kind             │
└─────────────────────────────────────────────────────────────────┘
                            ▲ writes
                            │
┌─────────────────────────────────────────────────────────────────┐
│              ClientSyncEngine (Background)                       │
│  - Scheduled relay polling per registered client                │
│  - Ingests new/updated events into nostrdb                      │
│  - Detects state changes for SSE notification                   │
│  - Runs independently of request handling                       │
└───────────────────────────┬─────────────────────────────────────┘
                            │ queries
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│              Nostr Relays (WebSocket)                            │
│  - Used ONLY by background sync, never by request handlers      │
│  - Fetches kind 30078 events filtered by author pubkey          │
└─────────────────────────────────────────────────────────────────┘
```

### Why Storage-First?

| Concern | Relay-on-demand (web module) | Storage-first (client API) |
|---------|------------------------------|----------------------------|
| Latency per request | 500ms–5s (network) | <10ms (local LMDB) |
| Aggregation queries | Impossible without full scan | Fast: iterate local index |
| Analytics | N/A | Computed from local data |
| Pagination | Unreliable (relay limits) | Deterministic (local sort) |
| Availability | Depends on relay uptime | Works during relay outage |
| Rate limits | Subject to relay rate limits | Only local I/O |

### nostrdb Query Patterns by Endpoint

Each endpoint maps to specific nostrdb queries. The table below shows which `EventStore` method serves each endpoint and its expected performance:

| Endpoint | Primary Query | nostrdb Method | Performance |
|----------|---------------|----------------|-------------|
| `GET /summary` | All vouchers by author | `findByAuthor(pubkey, limit)` | **Fast** (author index) |
| `GET /vouchers` | All vouchers by author | `findByAuthor(pubkey, limit)` | **Fast** (author index) |
| `GET /vouchers/{id}` | Single voucher by d-tag | `findLatestByVoucherId(id)` | **Fast** (d-tag index) |
| `GET /vouchers/{id}/history` | All events for voucher | `findAllByVoucherId(id, limit)` | **Fast** (d-tag index) |
| `GET /vouchers/{id}/tree` | Voucher + parents/children | `findLatestByVoucherId` + `findChildrenByParentId` | **Fast/Medium** |
| `GET /vouchers/{id}/verify` | Voucher + tree + history | Combined queries | **Medium** |
| `GET /activity` | All vouchers by author, sorted | `findByAuthor(pubkey, limit)` + in-memory sort | **Fast** |
| `GET /expiring` | All vouchers by author, filtered | `findByAuthor(pubkey, limit)` + in-memory filter | **Fast** |
| `GET /analytics/issuance` | All vouchers by author | `findByAuthor(pubkey, limit)` + in-memory aggregation | **Fast** |
| `GET /received` | All vouchers by author (as claimant) | `findByAuthor(pubkey, limit)` + in-memory filter | **Fast** (see note) |
| `GET /unclaimed` | All vouchers by author, status=ISSUED | `findByAuthor(pubkey, limit)` + in-memory filter | **Fast** |
| `GET /export` | All vouchers by author | `findByAuthor(pubkey, limit)` | **Fast** |
| `GET /watch` | Driven by sync engine | No direct query — sync engine pushes changes | **N/A** |

**Note on "received" queries:** nostrdb indexes events by `author` (the event publisher, which is the issuer). To find vouchers where the client is the *recipient* (`claimedBy`), we cannot use the author index directly. Instead, the sync engine must also subscribe to events that reference the client's pubkey in `claimed_by` tags. See [Recipient Discovery](#recipient-discovery) below.

### Background Sync Engine

The `ClientSyncEngine` is a scheduled background service that keeps nostrdb populated with fresh data from Nostr relays. It runs independently of API request handling.

#### Client Registration

When a client authenticates for the first time, their pubkey is registered with the sync engine. The engine maintains a set of active client pubkeys and syncs their data on a schedule.

```java
// Triggered by the NIP-98 auth filter on successful authentication
syncEngine.registerClient(pubkey);
```

**Deregistration:** Client pubkeys are deregistered after a configurable inactivity period. The timeout is **tiered** based on whether the pubkey has associated data:

| Client Type | Inactivity Timeout | Criteria |
|-------------|-------------------|----------|
| **Known client** | 24 hours | Pubkey appears as issuer, sender, or recipient in at least one stored voucher |
| **Unknown client** | 1 hour | Pubkey has zero associated vouchers in nostrdb after initial sync |

This tiered approach mitigates Sybil attacks where an adversary authenticates with many random keys to exhaust the sync queue (see [Anti-Sybil Protection](#anti-sybil-protection)).

#### Anti-Sybil Protection

The sync engine uses a **priority queue** to allocate relay query bandwidth fairly and resist resource exhaustion attacks:

**Priority tiers:**

| Priority | Description | Sync Frequency |
|----------|-------------|----------------|
| **P0 — Active + Known** | Client made an API request in the last 5 minutes AND has vouchers in nostrdb | Every sync cycle (30s) |
| **P1 — Known** | Client has vouchers in nostrdb but no recent API activity | Every 2nd cycle (60s) |
| **P2 — Active + Unknown** | Client recently authenticated but has no vouchers in nostrdb yet | Every 3rd cycle (90s) |
| **P3 — Unknown + Idle** | No vouchers, no recent activity — pending deregistration | Paused (deregistered after 1h) |

**Registration cap enforcement:**
- When the `max-registered-clients` limit is reached, new registrations evict the lowest-priority client (P3 first, then P2).
- P0 and P1 clients are never evicted by new registrations. If the cap is fully occupied by P0/P1 clients, new unknown clients receive a `503 SERVICE_BUSY` response.
- The cap applies to concurrently synced clients, not to API access — authenticated requests still work for unregistered clients via on-demand relay fallback, but without background sync.

#### Sync Cycle

Each sync cycle performs the following for every registered client (subject to priority scheduling):

```
1. Query relays: Filter { kinds: [30078], authors: [pubkey], since: lastSyncTimestamp }
   (Discover vouchers issued by this client)
2. Query relays: Filter { kinds: [30078], "#sent_by": [pubkey], since: lastSyncTimestamp }
   (Discover vouchers sent by this client but authored by another key)
3. Query relays: Filter { kinds: [30078], "#claimed_by": [pubkey], since: lastSyncTimestamp }
   (Discover vouchers received by this client)
4. For each new/updated event across all three queries:
   a. Store in nostrdb via EventStore.store(event, relayUrl)
   b. Map to VoucherNode, compare against last known state
   c. If state changed: emit to SSE subscribers (if any)
5. Update lastSyncTimestamp for this client
6. Update client priority tier based on voucher count
```

#### Sync Schedule

| Event | Interval | Description |
|-------|----------|-------------|
| **Periodic sync** | Every 30 seconds | Poll relays for all registered clients |
| **Initial sync** | On first registration | Full historical fetch (no `since` filter, up to configured limit) |
| **On-demand sync** | On cache miss | If a request queries a voucher not in nostrdb, trigger a targeted relay fetch |

#### Recipient Discovery

Finding vouchers *received* by a client requires a different relay query pattern since the `claimed_by` tag is not the event author:

```
Filter { kinds: [30078], "#claimed_by": [clientPubkey], since: lastSyncTimestamp }
```

This relies on the relay supporting generic tag filtering (NIP-01 compliant relays index all single-letter tags and `claimed_by` would need to be queried via a custom tag code). If the relay does not support this filter, the sync engine falls back to discovering received vouchers when the client explicitly queries a voucher by ID (on-demand sync).

### In-Memory Computed Cache

Aggregated results (summary, analytics) are expensive to compute even from local nostrdb data, since they require iterating all of a client's vouchers and performing in-memory grouping/counting. These results are cached in memory with a TTL:

| Cache Key | TTL | Invalidation |
|-----------|-----|--------------|
| `summary:{pubkey}` | 30 seconds | Evicted when sync engine detects state change for this pubkey |
| `analytics:{pubkey}:{params}` | 5 minutes | Time-based expiry only |
| `expiring:{pubkey}` | 60 seconds | Time-based expiry only |

The sync engine emits an internal event when it detects a state change for a pubkey, which invalidates the summary cache entry so the next request recomputes it from fresh nostrdb data.

### Data Freshness Guarantees

| Scenario | Maximum Staleness | Explanation |
|----------|-------------------|-------------|
| Client actively using API | ~30 seconds | Periodic sync interval |
| First request after long absence | Seconds (initial sync) | Full sync triggered on registration |
| Voucher queried by ID (not in store) | Seconds (on-demand fetch) | Falls back to relay for individual voucher |
| Client inactive >24 hours | Unbounded | Sync stops; resumes on next auth |

### Storage Sizing

Estimate for nostrdb storage requirements:

| Metric | Value |
|--------|-------|
| Average event size | ~1 KB |
| Events per voucher (lifecycle) | ~3–5 |
| 1,000 vouchers | ~5 MB |
| 10,000 vouchers | ~50 MB |
| 100,000 vouchers | ~500 MB |
| Default max DB size | 512 MB |

For deployments expecting >100K vouchers across all clients, increase `max-size-bytes` accordingly.

---

## API Endpoints

Base path: `/api/v1/client`

All endpoints require NIP-98 authentication. The authenticated pubkey is referred to as `{client}` below.

---

### 1. Portfolio Summary

Aggregated overview of the client's voucher portfolio.

```
GET /api/v1/client/summary
```

#### Response

```json
{
  "pubkey": "a1b2c3d4...",
  "totalVouchers": 47,
  "byStatus": {
    "ISSUED": 5,
    "CLAIMED": 12,
    "SPLIT": 8,
    "REDEEMED": 18,
    "RECLAIMED": 2,
    "EXPIRED": 2,
    "REVOKED": 0
  },
  "byRole": {
    "issued": 30,
    "received": 17
  },
  "activeValue": {
    "EUR": {
      "faceValue": 15000,
      "faceDecimals": 2,
      "tokenAmount": 15000,
      "voucherCount": 17
    },
    "USD": {
      "faceValue": 5000,
      "faceDecimals": 2,
      "tokenAmount": 4800,
      "voucherCount": 3
    }
  },
  "generatedAt": "2026-03-15T10:30:00Z"
}
```

**Notes:**
- `activeValue` includes only non-terminal vouchers (ISSUED + CLAIMED).
- `byRole.issued` counts vouchers where `issuerPublicKey == client`.
- `byRole.received` counts vouchers where `claimedBy == client` and `issuerPublicKey != client`.

---

### 2. List Vouchers

Paginated listing of the client's vouchers with filtering.

```
GET /api/v1/client/vouchers
```

#### Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `role` | string | `issuer`, `recipient`, or `all` | `all` |
| `status` | string | Filter by status (comma-separated for multiple) | all statuses |
| `unit` | string | Filter by currency unit | all units |
| `since` | string | Issued after date (ISO-8601) | none |
| `until` | string | Issued before date (ISO-8601) | none |
| `minFaceValue` | long | Minimum face value (minor units) | none |
| `maxFaceValue` | long | Maximum face value (minor units) | none |
| `sortBy` | string | `issuedAt`, `faceValue`, `status`, `expiresAt` | `issuedAt` |
| `sortOrder` | string | `asc` or `desc` | `desc` |
| `limit` | integer | Results per page | `50` |
| `offset` | integer | Pagination offset | `0` |

#### Response

```json
{
  "vouchers": [
    {
      "voucherId": "v-1766748473969",
      "status": "CLAIMED",
      "role": "issuer",
      "faceValue": 1000,
      "faceDecimals": 2,
      "tokenAmount": 1000,
      "unit": "EUR",
      "issuerId": "merchant-001",
      "issuedAt": "2026-03-10T10:30:00Z",
      "expiresAt": "2026-04-10T10:30:00Z",
      "memo": "Payment for services",
      "claimedBy": "b2c3d4e5...",
      "claimedAt": "2026-03-10T14:15:30Z"
    }
  ],
  "total": 47,
  "limit": 50,
  "offset": 0
}
```

---

### 3. Voucher Detail

Full detail view of a specific voucher owned by the client.

```
GET /api/v1/client/vouchers/{voucherId}
```

#### Response

Returns the full `VoucherNode` representation (same as the public API), but returns `403` if the client has no relationship to the voucher.

#### Status Codes

| Code | Description |
|------|-------------|
| 200 | Success |
| 403 | Client has no relationship to this voucher |
| 404 | Voucher not found |

---

### 4. Voucher History

State transition history for a voucher the client owns.

```
GET /api/v1/client/vouchers/{voucherId}/history
```

#### Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `since` | string | Filter transitions after date (ISO-8601) | none |
| `until` | string | Filter transitions before date (ISO-8601) | none |
| `limit` | integer | Maximum transitions to return | `100` |

#### Response

```json
{
  "voucherId": "v-1766748473969",
  "transitions": [
    {
      "fromStatus": null,
      "toStatus": "ISSUED",
      "stateVersion": 0,
      "transitionAt": "2026-03-10T10:30:00Z",
      "transitionActor": "ISSUER",
      "transitionReason": null
    },
    {
      "fromStatus": "ISSUED",
      "toStatus": "CLAIMED",
      "stateVersion": 1,
      "transitionAt": "2026-03-10T14:15:30Z",
      "transitionActor": "RECIPIENT",
      "claimedBy": "b2c3d4e5...",
      "claimedAt": "2026-03-10T14:15:30Z"
    }
  ],
  "warnings": []
}
```

---

### 5. Voucher Tree

Hierarchy view for a voucher the client owns.

```
GET /api/v1/client/vouchers/{voucherId}/tree
```

#### Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `depth` | integer | Maximum traversal depth | `10` |
| `direction` | string | `up`, `down`, or `both` | `both` |

#### Response

Same structure as the public `VoucherTree` response. Access-controlled to vouchers where the client is issuer, sender, or recipient of the target voucher.

#### Tree Visibility Scoping

The tree endpoint applies **role-based pruning** to prevent privacy leaks across split hierarchies:

**Problem:** If Alice splits a 100-unit voucher into 40 (sent to Bob) and 60 (kept by Alice), and Bob queries the tree for his 40-unit voucher, an unscoped response would reveal Alice's 60-unit "change" voucher — leaking her balance.

**Scoping rules by role:**

| Client Role | Upward (ancestors) | Downward (descendants) | Siblings |
|-------------|-------------------|----------------------|----------|
| **Issuer** | Full visibility | Full visibility | Full visibility |
| **Sender** | Full visibility | Full visibility | Full visibility |
| **Recipient** | Full visibility (to verify lineage) | Only the client's own sub-tree | **Redacted** |

**Redaction behavior:**
- When a recipient traverses upward to a parent voucher, the parent node is visible (to verify the source and value conservation).
- However, the parent's `children` list is filtered to include **only** the branch that leads to the client's voucher. Other children (siblings) are omitted from the response.
- The response includes a `redacted` boolean field on the parent node indicating that siblings were removed, so the client knows the tree is not complete.

**Example:** Bob (recipient) queries tree for `v-child-40`:

```json
{
  "target": "v-child-40",
  "root": "v-parent-100",
  "nodes": {
    "v-parent-100": {
      "voucherId": "v-parent-100",
      "faceValue": 10000,
      "status": "SPLIT",
      "redacted": true
    },
    "v-child-40": {
      "voucherId": "v-child-40",
      "faceValue": 4000,
      "status": "CLAIMED",
      "redacted": false
    }
  },
  "childrenMap": {
    "v-parent-100": ["v-child-40"]
  },
  "depth": 2,
  "totalNodes": 2
}
```

Alice's `v-child-60` is not visible. The `redacted: true` flag on the parent tells Bob that siblings exist but are hidden.

---

### 6. Verify Voucher

Run integrity checks on a voucher the client owns.

```
GET /api/v1/client/vouchers/{voucherId}/verify
```

#### Response

```json
{
  "voucherId": "v-1766748473969",
  "found": true,
  "signatureValid": true,
  "valueConserved": true,
  "hierarchyComplete": true,
  "stateTransitionsValid": true,
  "message": "All checks passed",
  "auditIssues": []
}
```

---

### 7. Activity Feed

Chronological stream of recent state transitions across all of the client's vouchers.

```
GET /api/v1/client/activity
```

#### Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `since` | string | Transitions after date (ISO-8601) | 7 days ago |
| `until` | string | Transitions before date (ISO-8601) | now |
| `role` | string | `issuer`, `recipient`, or `all` | `all` |
| `limit` | integer | Maximum events | `100` |

#### Response

```json
{
  "events": [
    {
      "voucherId": "v-1766748473969",
      "fromStatus": "ISSUED",
      "toStatus": "CLAIMED",
      "transitionAt": "2026-03-14T18:22:00Z",
      "transitionActor": "RECIPIENT",
      "faceValue": 1000,
      "unit": "EUR",
      "memo": "Payment for services"
    },
    {
      "voucherId": "v-1766748474001",
      "fromStatus": "CLAIMED",
      "toStatus": "REDEEMED",
      "transitionAt": "2026-03-14T16:10:00Z",
      "transitionActor": "RECIPIENT",
      "faceValue": 500,
      "unit": "EUR",
      "memo": null
    }
  ],
  "total": 23,
  "limit": 100
}
```

---

### 8. Expiring Vouchers

List vouchers approaching their expiration date.

```
GET /api/v1/client/expiring
```

#### Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `within` | string | Duration window (ISO-8601 duration: `P7D`, `P1M`) | `P7D` |
| `role` | string | `issuer`, `recipient`, or `all` | `all` |
| `limit` | integer | Maximum results | `50` |

#### Response

```json
{
  "vouchers": [
    {
      "voucherId": "v-1766748473969",
      "status": "ISSUED",
      "faceValue": 1000,
      "unit": "EUR",
      "expiresAt": "2026-03-18T10:30:00Z",
      "expiresIn": "PT71H30M",
      "role": "issuer",
      "memo": "Payment for services"
    }
  ],
  "window": "P7D",
  "total": 1
}
```

---

### 9. Issuance Analytics

Aggregated statistics about vouchers the client has issued.

```
GET /api/v1/client/analytics/issuance
```

#### Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `since` | string | Period start (ISO-8601) | 30 days ago |
| `until` | string | Period end (ISO-8601) | now |
| `unit` | string | Filter by currency unit | all units |
| `granularity` | string | `day`, `week`, or `month` | `day` |

#### Response

```json
{
  "period": {
    "since": "2026-02-13T00:00:00Z",
    "until": "2026-03-15T23:59:59Z"
  },
  "totals": {
    "issued": 30,
    "claimed": 22,
    "redeemed": 15,
    "split": 5,
    "expired": 2,
    "reclaimed": 1,
    "revoked": 0
  },
  "claimRate": 0.733,
  "redemptionRate": 0.682,
  "averageFaceValue": {
    "EUR": 1250,
    "USD": 2000
  },
  "totalFaceValueIssued": {
    "EUR": 37500,
    "USD": 10000
  },
  "averageTimeToClaimSeconds": 14400,
  "averageTimeToRedeemSeconds": 86400,
  "timeSeries": [
    {
      "period": "2026-03-14",
      "issued": 3,
      "claimed": 2,
      "redeemed": 1,
      "faceValueIssued": { "EUR": 3000 }
    },
    {
      "period": "2026-03-15",
      "issued": 1,
      "claimed": 0,
      "redeemed": 2,
      "faceValueIssued": { "EUR": 1000 }
    }
  ]
}
```

**Definitions:**
- `claimRate`: `claimed / issued` over the period (includes vouchers that were later split or redeemed).
- `redemptionRate`: `redeemed / claimed` over the period.
- `averageTimeToClaimSeconds`: Mean duration from `issuedAt` to `claimedAt` for vouchers claimed within the period.
- `averageTimeToRedeemSeconds`: Mean duration from `claimedAt` to `redeemedAt` for vouchers redeemed within the period.

---

### 10. Received Vouchers

List vouchers received by the client (where the client claimed a voucher issued by someone else).

```
GET /api/v1/client/received
```

#### Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `status` | string | Filter by current status (comma-separated) | all |
| `unit` | string | Filter by currency unit | all |
| `since` | string | Claimed after date (ISO-8601) | none |
| `until` | string | Claimed before date (ISO-8601) | none |
| `limit` | integer | Results per page | `50` |
| `offset` | integer | Pagination offset | `0` |

#### Response

```json
{
  "vouchers": [
    {
      "voucherId": "v-1766748474001",
      "status": "CLAIMED",
      "faceValue": 500,
      "faceDecimals": 2,
      "tokenAmount": 500,
      "unit": "EUR",
      "issuerId": "other-merchant",
      "issuerPublicKey": "c3d4e5f6...",
      "claimedAt": "2026-03-12T09:00:00Z",
      "expiresAt": "2026-04-12T09:00:00Z",
      "memo": "Refund"
    }
  ],
  "total": 17,
  "limit": 50,
  "offset": 0
}
```

---

### 11. Unclaimed Vouchers

List vouchers the client issued or sent that have not yet been claimed.

```
GET /api/v1/client/unclaimed
```

#### Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `unit` | string | Filter by currency unit | all |
| `expiredOnly` | boolean | Show only expired unclaimed vouchers | `false` |
| `sortBy` | string | `issuedAt`, `expiresAt`, `faceValue` | `issuedAt` |
| `sortOrder` | string | `asc` or `desc` | `desc` |
| `limit` | integer | Results per page | `50` |
| `offset` | integer | Pagination offset | `0` |

#### Response

```json
{
  "vouchers": [
    {
      "voucherId": "v-1766748473969",
      "faceValue": 1000,
      "faceDecimals": 2,
      "tokenAmount": 1000,
      "unit": "EUR",
      "issuedAt": "2026-03-10T10:30:00Z",
      "expiresAt": "2026-04-10T10:30:00Z",
      "ageSeconds": 432000,
      "memo": "Payment for services",
      "reclaimable": true
    }
  ],
  "totalUnclaimedValue": {
    "EUR": { "faceValue": 1000, "tokenAmount": 1000 }
  },
  "total": 5,
  "limit": 50,
  "offset": 0
}
```

---

### 12. Reclaim Voucher

Initiate reclaim of an unclaimed voucher. This is a **write operation** that triggers a proof swap at the mint and publishes a state transition to the relay.

```
POST /api/v1/client/unclaimed/{voucherId}/reclaim
```

#### Request Body

```json
{
  "tokenData": "cashuBo2Ftd2h0dHA..."
}
```

The `tokenData` field contains the original Cashu token (V4 encoded) that the client held for the unclaimed voucher.

#### Response

```json
{
  "voucherId": "v-1766748473969",
  "success": true,
  "newVoucherId": "v-1766748475000",
  "message": "Voucher reclaimed. New voucher v-1766748475000 created in CLAIMED state."
}
```

#### Status Codes

| Code | Description |
|------|-------------|
| 200 | Reclaim successful |
| 400 | Invalid token data |
| 403 | Client is not the sender of this voucher |
| 404 | Voucher not found |
| 409 | Voucher already claimed or in terminal state |
| 502 | Mint unavailable for proof swap |

#### Prerequisites

- Voucher must be in `ISSUED` state
- Client must be the original sender (verified via NIP-98 pubkey)
- Token proofs must still be valid at the mint (not yet spent)

---

### 13. Revoke Voucher

Revoke a voucher the client issued. Only applicable to vouchers in `ISSUED` state (not yet claimed).

```
POST /api/v1/client/vouchers/{voucherId}/revoke
```

#### Request Body

```json
{
  "reason": "Sent to wrong recipient"
}
```

#### Response

```json
{
  "voucherId": "v-1766748473969",
  "success": true,
  "previousStatus": "ISSUED",
  "newStatus": "REVOKED",
  "transitionAt": "2026-03-15T11:00:00Z",
  "message": "Voucher revoked."
}
```

#### Status Codes

| Code | Description |
|------|-------------|
| 200 | Revocation successful |
| 403 | Client is not the issuer of this voucher |
| 404 | Voucher not found |
| 409 | Voucher already claimed or in terminal state |

---

### 14. Watch Activity (SSE)

Server-Sent Events stream for real-time state changes across all of the client's vouchers.

```
GET /api/v1/client/watch
```

#### Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `role` | string | `issuer`, `recipient`, or `all` | `all` |

#### Event Types

```
event: status-change
data: {"voucherId":"v-1766748473969","fromStatus":"ISSUED","toStatus":"CLAIMED","transitionAt":"2026-03-15T10:30:00Z","transitionActor":"RECIPIENT","faceValue":1000,"unit":"EUR"}

event: heartbeat
data: {"timestamp":"2026-03-15T10:31:00Z","watching":47}

event: expiring-soon
data: {"voucherId":"v-1766748474002","status":"ISSUED","expiresAt":"2026-03-16T00:00:00Z","expiresIn":"PT13H30M","faceValue":2000,"unit":"EUR"}
```

**Behavior:**
- Subscribes to the sync engine's internal change stream for the client's pubkey
- The sync engine (not the SSE handler) polls relays every 30 seconds in the background
- Emits `status-change` when the sync engine detects a state version increment
- Emits `expiring-soon` when the sync engine detects a voucher entering the 24-hour expiration window
- Sends `heartbeat` every 30 seconds
- Connection stays open until the client disconnects or a server-configured maximum duration (default: 30 minutes)
- No relay round-trips occur during SSE event delivery — all data comes from nostrdb via the sync engine

---

### 15. Export

Export the client's voucher data for external bookkeeping or compliance.

```
GET /api/v1/client/export
```

#### Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `format` | string | `json` or `csv` | `json` |
| `role` | string | `issuer`, `recipient`, or `all` | `all` |
| `status` | string | Filter by status (comma-separated) | all |
| `since` | string | Issued after date (ISO-8601) | none |
| `until` | string | Issued before date (ISO-8601) | none |
| `includeHistory` | boolean | Include state transition history per voucher | `false` |

#### Response Headers

```
Content-Type: application/json (or text/csv)
Content-Disposition: attachment; filename="ledger-export-2026-03-15.json"
```

#### CSV Columns

```
voucher_id,status,role,face_value,face_decimals,token_amount,unit,issuer_id,issued_at,expires_at,claimed_by,claimed_at,redeemed_at,memo
```

---

### 16. Batch Revoke

Revoke multiple unclaimed vouchers in a single request (e.g., cancelling a campaign).

```
POST /api/v1/client/vouchers/batch-revoke
```

#### Request Body

```json
{
  "voucherIds": ["v-100", "v-101", "v-102"],
  "reason": "Campaign cancelled"
}
```

#### Response

```json
{
  "results": [
    { "voucherId": "v-100", "success": true, "newStatus": "REVOKED" },
    { "voucherId": "v-101", "success": true, "newStatus": "REVOKED" },
    { "voucherId": "v-102", "success": false, "error": "VOUCHER_NOT_REVOKABLE", "message": "Voucher already claimed" }
  ],
  "succeeded": 2,
  "failed": 1
}
```

**Constraints:**
- Maximum 100 voucher IDs per request.
- Each voucher is validated independently — partial success is possible.
- The client must be the issuer of every voucher in the batch.

---

### 17. Batch Reclaim

Reclaim multiple unclaimed vouchers in a single request.

```
POST /api/v1/client/unclaimed/batch-reclaim
```

#### Request Body

```json
{
  "vouchers": [
    { "voucherId": "v-100", "tokenData": "cashuBo2Ftd2h0dHA..." },
    { "voucherId": "v-101", "tokenData": "cashuBo2Ftd2h0dHA..." }
  ]
}
```

#### Response

```json
{
  "results": [
    { "voucherId": "v-100", "success": true, "newVoucherId": "v-200" },
    { "voucherId": "v-101", "success": false, "error": "PROOFS_ALREADY_SPENT", "message": "Token proofs already spent" }
  ],
  "succeeded": 1,
  "failed": 1
}
```

**Constraints:**
- Maximum 50 vouchers per request (each requires a mint round-trip).
- The client must be the sender of every voucher.

---

### 18. Liabilities Summary

Outstanding float and liability breakdown for issuers. Shows how much value the client has issued that remains unclaimed or unredeemed.

```
GET /api/v1/client/analytics/liabilities
```

#### Query Parameters

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `unit` | string | Filter by currency unit | all units |

#### Response

```json
{
  "pubkey": "a1b2c3d4...",
  "liabilities": {
    "EUR": {
      "totalIssuedFaceValue": 50000,
      "faceDecimals": 2,
      "outstanding": {
        "unclaimed": { "faceValue": 5000, "tokenAmount": 5000, "count": 5 },
        "claimed": { "faceValue": 12000, "tokenAmount": 12000, "count": 12 },
        "totalFloat": { "faceValue": 17000, "tokenAmount": 17000, "count": 17 }
      },
      "settled": {
        "redeemed": { "faceValue": 18000, "tokenAmount": 18000, "count": 18 },
        "reclaimed": { "faceValue": 2000, "tokenAmount": 2000, "count": 2 },
        "expired": { "faceValue": 2000, "tokenAmount": 2000, "count": 2 },
        "revoked": { "faceValue": 0, "tokenAmount": 0, "count": 0 }
      },
      "split": { "faceValue": 11000, "tokenAmount": 11000, "count": 8 }
    }
  },
  "generatedAt": "2026-03-15T10:30:00Z"
}
```

**Definitions:**
- `outstanding.totalFloat`: Sum of `unclaimed` + `claimed` — value still "in flight" that has not reached a terminal state.
- `settled`: Value that has reached a terminal state (redeemed, reclaimed, expired, revoked).
- `split`: Value that transitioned through SPLIT. Note that split vouchers redistribute value to children, so these amounts are also accounted for in the children's categories.

---

### 19. Voucher Metadata Labels

Store private, client-side labels on vouchers (e.g., internal order IDs, customer names). These labels are stored locally in nostrdb — they are **never published to Nostr relays**.

```
PUT /api/v1/client/vouchers/{voucherId}/metadata
```

#### Request Body

```json
{
  "labels": {
    "orderId": "ORD-2026-1234",
    "customer": "Acme Corp",
    "campaign": "spring-promo"
  }
}
```

#### Response

```json
{
  "voucherId": "v-1766748473969",
  "labels": {
    "orderId": "ORD-2026-1234",
    "customer": "Acme Corp",
    "campaign": "spring-promo"
  },
  "updatedAt": "2026-03-15T10:30:00Z"
}
```

**Retrieval:** Labels are included in the `VoucherListItem` and voucher detail responses when present:

```json
{
  "voucherId": "v-1766748473969",
  "status": "CLAIMED",
  "labels": { "orderId": "ORD-2026-1234", "customer": "Acme Corp" },
  ...
}
```

**Filtering:** The `GET /vouchers` endpoint supports a `label` query parameter to filter by label key-value pairs:

```
GET /api/v1/client/vouchers?label=orderId:ORD-2026-1234
GET /api/v1/client/vouchers?label=campaign:spring-promo
```

**Storage:** Labels are stored as a separate nostrdb entry (kind 30079 or a local-only storage table) keyed by `{clientPubkey}:{voucherId}`. They are scoped to the client — different clients can label the same voucher independently.

**Constraints:**
- Maximum 10 labels per voucher.
- Label keys: 1–32 characters, alphanumeric and hyphens only.
- Label values: 1–256 characters.
- Total label payload must not exceed 4 KB.

---

### 20. Sync Status

Show the state of the client's background sync.

```
GET /api/v1/client/sync/status
```

#### Response

```json
{
  "pubkey": "a1b2c3d4...",
  "registered": true,
  "priority": "P0",
  "lastSyncAt": "2026-03-15T10:29:45Z",
  "lastSyncDurationMs": 340,
  "eventsDiscovered": {
    "asIssuer": 30,
    "asSender": 5,
    "asRecipient": 17
  },
  "relays": [
    {
      "url": "wss://relay.imani.casa",
      "status": "CONNECTED",
      "lastResponseAt": "2026-03-15T10:29:45Z",
      "eventsFromRelay": 52
    }
  ],
  "nextSyncAt": "2026-03-15T10:30:15Z"
}
```

---

### 21. Webhook Registration

Register a webhook URL to receive POST callbacks on voucher state transitions. Suitable for backend integrations (e.g., e-commerce order fulfillment) where SSE is impractical.

```
POST /api/v1/client/webhooks
```

#### Request Body

```json
{
  "url": "https://shop.example.com/hooks/cashu",
  "secret": "whsec_a1b2c3d4e5f6...",
  "events": ["status-change", "expiring-soon"],
  "filter": {
    "role": "issuer",
    "status": ["CLAIMED", "REDEEMED"]
  }
}
```

#### Response

```json
{
  "webhookId": "wh-abc123",
  "url": "https://shop.example.com/hooks/cashu",
  "events": ["status-change", "expiring-soon"],
  "filter": { "role": "issuer", "status": ["CLAIMED", "REDEEMED"] },
  "createdAt": "2026-03-15T10:30:00Z",
  "active": true
}
```

#### Webhook Payload

The server POSTs a JSON payload to the registered URL, signed with the shared secret via HMAC-SHA256 in the `X-Webhook-Signature` header:

```
POST https://shop.example.com/hooks/cashu
X-Webhook-Signature: sha256=abc123...
Content-Type: application/json

{
  "webhookId": "wh-abc123",
  "event": "status-change",
  "data": {
    "voucherId": "v-1766748473969",
    "fromStatus": "ISSUED",
    "toStatus": "CLAIMED",
    "transitionAt": "2026-03-15T10:30:00Z",
    "transitionActor": "RECIPIENT",
    "faceValue": 1000,
    "unit": "EUR"
  },
  "deliveredAt": "2026-03-15T10:30:01Z"
}
```

#### Webhook Management

```
GET    /api/v1/client/webhooks              # List registered webhooks
GET    /api/v1/client/webhooks/{webhookId}  # Get webhook details
DELETE /api/v1/client/webhooks/{webhookId}  # Delete a webhook
```

**Delivery behavior:**
- Retries on failure: 3 attempts with exponential backoff (1s, 10s, 60s).
- Webhook is automatically deactivated after 10 consecutive delivery failures.
- Maximum 5 registered webhooks per client.
- The `secret` is stored hashed — it cannot be retrieved after creation.

---

## Multi-Key Delegation (NIP-26)

### Overview

Issuers often use separate Nostr keys for different systems (e.g., POS terminals, automated issuance, different store locations) but need a consolidated view from a single "master" key. The client API supports [NIP-26 (Delegated Event Signing)](https://github.com/nostr-protocol/nips/blob/master/26.md) to enable this.

### How It Works

A master key creates a delegation token authorizing a worker key to act on its behalf:

```json
{
  "kind": 27235,
  "pubkey": "<worker_pubkey>",
  "tags": [
    ["delegation", "<master_pubkey>", "kind=27235", "<delegation_signature>"],
    ["u", "https://ledger.example.com/api/v1/client/summary"],
    ["method", "GET"]
  ]
}
```

The server:
1. Verifies the NIP-98 event signature against the worker pubkey.
2. Detects the `delegation` tag and verifies the delegation signature against the master pubkey.
3. Uses the **master pubkey** as the client identity for the request.
4. The sync engine syncs data for both the master pubkey and all known worker pubkeys.

### Registering Delegated Keys

```
POST /api/v1/client/delegations
```

#### Request Body

Authenticated as the master key:

```json
{
  "workerPubkeys": ["worker1_pubkey_hex", "worker2_pubkey_hex"],
  "conditions": "kind=30078"
}
```

This tells the sync engine to also track vouchers issued by the worker keys and include them in the master key's aggregated views.

#### Response

```json
{
  "masterPubkey": "master_pubkey_hex",
  "delegatedKeys": ["worker1_pubkey_hex", "worker2_pubkey_hex"],
  "registeredAt": "2026-03-15T10:30:00Z"
}
```

#### Management

```
GET    /api/v1/client/delegations              # List delegated worker keys
DELETE /api/v1/client/delegations/{workerPubkey}  # Revoke delegation
```

### Impact on Queries

When a master key queries any endpoint, the results include vouchers where **any** of the master's registered worker keys is the issuer, sender, or recipient. The `role` field in responses disambiguates:

```json
{
  "voucherId": "v-123",
  "role": "issuer",
  "actingKey": "worker1_pubkey_hex",
  ...
}
```

---

## Data Model

### DTOs

#### ClientSummary

| Field | Type | Description |
|-------|------|-------------|
| `pubkey` | String | Client's Nostr public key |
| `totalVouchers` | int | Total voucher count |
| `byStatus` | Map<VoucherStatus, Integer> | Count per status |
| `byRole` | RoleCounts | Count by issuer vs recipient role |
| `activeValue` | Map<String, UnitSummary> | Aggregated value of non-terminal vouchers per currency |
| `generatedAt` | Instant | Timestamp of the summary computation |

#### UnitSummary

| Field | Type | Description |
|-------|------|-------------|
| `faceValue` | long | Total face value in minor units |
| `faceDecimals` | int | Decimal places for the unit |
| `tokenAmount` | long | Total token amount |
| `voucherCount` | int | Number of vouchers in this unit |

#### VoucherListItem

| Field | Type | Description |
|-------|------|-------------|
| `voucherId` | String | Voucher identifier |
| `status` | VoucherStatus | Current status |
| `role` | String | Client's relationship: `issuer` or `recipient` |
| `faceValue` | long | Face value in minor units |
| `faceDecimals` | int | Decimal places |
| `tokenAmount` | long | Token amount |
| `unit` | String | Currency unit |
| `issuerId` | String | Issuer identifier |
| `issuedAt` | Instant | When issued |
| `expiresAt` | Instant | When it expires (nullable) |
| `memo` | String | Description (nullable) |
| `claimedBy` | String | Recipient pubkey (nullable) |
| `claimedAt` | Instant | When claimed (nullable) |

#### ActivityEvent

| Field | Type | Description |
|-------|------|-------------|
| `voucherId` | String | Voucher identifier |
| `fromStatus` | VoucherStatus | Previous status |
| `toStatus` | VoucherStatus | New status |
| `transitionAt` | Instant | When the transition occurred |
| `transitionActor` | TransitionActor | Who initiated the transition |
| `faceValue` | long | Voucher face value for context |
| `unit` | String | Currency unit |
| `memo` | String | Voucher memo (nullable) |

#### IssuanceAnalytics

| Field | Type | Description |
|-------|------|-------------|
| `period` | DateRange | Query period |
| `totals` | Map<VoucherStatus, Integer> | Count per terminal state |
| `claimRate` | double | claimed / issued ratio |
| `redemptionRate` | double | redeemed / claimed ratio |
| `averageFaceValue` | Map<String, Long> | Average face value per unit |
| `totalFaceValueIssued` | Map<String, Long> | Total face value issued per unit |
| `averageTimeToClaimSeconds` | long | Mean issuance-to-claim duration |
| `averageTimeToRedeemSeconds` | long | Mean claim-to-redemption duration |
| `timeSeries` | List<PeriodBucket> | Bucketed data by granularity |

#### ReclaimRequest

| Field | Type | Description |
|-------|------|-------------|
| `tokenData` | String | Cashu V4-encoded token |

#### ReclaimResult

| Field | Type | Description |
|-------|------|-------------|
| `voucherId` | String | Original voucher ID |
| `success` | boolean | Whether reclaim succeeded |
| `newVoucherId` | String | New voucher ID (nullable) |
| `message` | String | Human-readable result message |

#### RevokeRequest

| Field | Type | Description |
|-------|------|-------------|
| `reason` | String | Reason for revocation |

#### RevokeResult

| Field | Type | Description |
|-------|------|-------------|
| `voucherId` | String | Voucher ID |
| `success` | boolean | Whether revocation succeeded |
| `previousStatus` | VoucherStatus | Status before revocation |
| `newStatus` | VoucherStatus | Status after (REVOKED) |
| `transitionAt` | Instant | Transition timestamp |
| `message` | String | Human-readable result |

#### BatchResult

| Field | Type | Description |
|-------|------|-------------|
| `results` | List<BatchItemResult> | Per-voucher outcome |
| `succeeded` | int | Number of successful operations |
| `failed` | int | Number of failed operations |

#### BatchItemResult

| Field | Type | Description |
|-------|------|-------------|
| `voucherId` | String | Voucher ID |
| `success` | boolean | Whether this item succeeded |
| `newStatus` | VoucherStatus | New status (nullable, on success) |
| `newVoucherId` | String | New voucher ID for reclaim (nullable) |
| `error` | String | Error code (nullable, on failure) |
| `message` | String | Human-readable message |

#### LiabilitySummary

| Field | Type | Description |
|-------|------|-------------|
| `pubkey` | String | Client's Nostr public key |
| `liabilities` | Map<String, UnitLiability> | Liability breakdown per currency unit |
| `generatedAt` | Instant | Computation timestamp |

#### UnitLiability

| Field | Type | Description |
|-------|------|-------------|
| `totalIssuedFaceValue` | long | Total face value ever issued in this unit |
| `faceDecimals` | int | Decimal places for the unit |
| `outstanding` | OutstandingBreakdown | Non-terminal voucher value |
| `settled` | SettledBreakdown | Terminal voucher value |
| `split` | ValueCount | Value transitioned through SPLIT |

#### VoucherLabels

| Field | Type | Description |
|-------|------|-------------|
| `voucherId` | String | Voucher identifier |
| `labels` | Map<String, String> | Key-value label pairs (max 10) |
| `updatedAt` | Instant | When labels were last modified |

#### SyncStatus

| Field | Type | Description |
|-------|------|-------------|
| `pubkey` | String | Client's Nostr public key |
| `registered` | boolean | Whether the client is registered with the sync engine |
| `priority` | String | Current priority tier (P0–P3) |
| `lastSyncAt` | Instant | Last successful sync timestamp |
| `lastSyncDurationMs` | long | Duration of last sync cycle |
| `eventsDiscovered` | RoleEventCounts | Events found by role |
| `relays` | List<RelayStatus> | Per-relay connectivity status |
| `nextSyncAt` | Instant | Scheduled next sync |

#### WebhookRegistration

| Field | Type | Description |
|-------|------|-------------|
| `webhookId` | String | Server-generated webhook identifier |
| `url` | String | Delivery URL (HTTPS required) |
| `events` | List<String> | Subscribed event types |
| `filter` | WebhookFilter | Optional role/status filter |
| `active` | boolean | Whether the webhook is active |
| `createdAt` | Instant | Registration timestamp |

---

## Access Control

### Ownership Rules

Every endpoint enforces that the authenticated client has a relationship to the queried data:

| Relationship | Condition | Allowed Operations |
|-------------|-----------|-------------------|
| **Issuer** | `event.pubkey == clientPubkey` (Nostr event author) | All read operations, revoke, full tree visibility |
| **Sender** | `sent_by` tag == `clientPubkey` (or author if tag absent) | All read operations, reclaim, full tree visibility |
| **Recipient** | `claimed_by` tag == `clientPubkey` | All read operations, restricted tree visibility (see [Tree Visibility Scoping](#tree-visibility-scoping)) |

### Scoping Behavior

- **List/search endpoints**: Automatically filter results to only include vouchers matching the client's pubkey in any ownership role.
- **Detail endpoints**: Return `403 FORBIDDEN` if the voucher exists but the client has no relationship to it. Return `404 NOT FOUND` if the voucher does not exist (prevents enumeration).
- **Write endpoints** (reclaim, revoke): Verify the specific required role (sender for reclaim, issuer for revoke) before proceeding.

### Rate Limiting

| Endpoint Category | Rate Limit |
|-------------------|------------|
| Summary, list, detail | 60 requests/minute per pubkey |
| Analytics | 10 requests/minute per pubkey |
| Export | 5 requests/minute per pubkey |
| Watch (SSE) | 2 concurrent connections per pubkey |
| Reclaim, revoke | 10 requests/minute per pubkey |

---

## Error Format

All errors follow the project's standard error response format:

```json
{
  "error": "VOUCHER_NOT_FOUND",
  "message": "Voucher v-invalid-id not found on any configured relay",
  "suggestion": "Verify the voucher ID and ensure it has been published to a connected relay.",
  "timestamp": "2026-03-15T10:30:00Z",
  "path": "/api/v1/client/vouchers/v-invalid-id"
}
```

### Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `AUTH_REQUIRED` | 401 | No Authorization header provided |
| `AUTH_INVALID` | 401 | Malformed NIP-98 event |
| `AUTH_SIGNATURE_INVALID` | 401 | Schnorr signature verification failed |
| `AUTH_EXPIRED` | 401 | Event timestamp outside acceptable window |
| `AUTH_URL_MISMATCH` | 401 | URL in event does not match request URL |
| `AUTH_METHOD_MISMATCH` | 401 | HTTP method in event does not match request |
| `AUTH_REPLAYED` | 401 | NIP-98 event ID has already been used (replay attack) |
| `ACCESS_DENIED` | 403 | Client has no relationship to the voucher |
| `VOUCHER_NOT_FOUND` | 404 | Voucher does not exist |
| `VOUCHER_NOT_RECLAIMABLE` | 409 | Voucher is not in ISSUED state |
| `VOUCHER_NOT_REVOKABLE` | 409 | Voucher is not in a revokable state |
| `PROOFS_ALREADY_SPENT` | 409 | Token proofs have already been spent at the mint |
| `MINT_UNAVAILABLE` | 502 | Cannot reach the mint for proof operations |
| `RELAY_UNAVAILABLE` | 503 | Cannot connect to configured relays |
| `RATE_LIMITED` | 429 | Too many requests |
| `INVALID_PARAMETER` | 400 | Invalid query parameter value |
| `BATCH_TOO_LARGE` | 400 | Batch request exceeds maximum size |
| `LABEL_LIMIT_EXCEEDED` | 400 | Too many labels on a voucher (max 10) |
| `WEBHOOK_LIMIT_EXCEEDED` | 400 | Too many registered webhooks (max 5) |
| `EXPORT_TOO_LARGE` | 413 | Export result exceeds maximum size |
| `SERVICE_BUSY` | 503 | Sync engine at capacity, cannot register new client |

---

## Configuration

### Application Properties

```yaml
ledger:
  client-api:
    port: 6061                                    # Separate port from public web module

    auth:
      timestamp-tolerance: 60s                    # Max clock drift for NIP-98 events
      require-url-match: true                     # Enforce URL tag verification
      require-method-match: true                  # Enforce method tag verification
      replay-cache-cleanup-interval: 30s          # How often to purge expired nonce entries
      nip26-delegation-enabled: true              # Support NIP-26 multi-key delegation

    rate-limit:
      enabled: true
      default-rpm: 60                             # Default requests per minute
      analytics-rpm: 10
      export-rpm: 5
      write-rpm: 10
      max-sse-connections: 2

    relay:
      urls:
        - wss://relay.imani.casa
      timeout: 30s

    # nostrdb storage — REQUIRED for the client API (not optional like in web module)
    storage:
      path: ${LEDGER_CLIENT_STORAGE_PATH:${user.home}/.cashu-ledger/client-ndb}
      max-size-bytes: 536870912                   # 512 MB
      event-ttl: 30d                              # Evict events older than 30 days

    # Background sync engine — keeps nostrdb populated from relays
    sync:
      interval: 30s                               # How often to poll relays (P0 clients)
      initial-fetch-limit: 500                    # Max events to fetch on first sync
      known-client-inactivity-timeout: 24h        # Deregister known clients after inactivity
      unknown-client-inactivity-timeout: 1h       # Deregister unknown clients (no vouchers) faster
      max-registered-clients: 1000                # Cap on concurrently synced clients
      batch-size: 50                              # Relay query batch size per cycle
      recipient-discovery-enabled: true           # Query relays for claimed_by tags
      sender-discovery-enabled: true              # Query relays for sent_by tags

    # Webhooks
    webhooks:
      max-per-client: 5                           # Maximum webhooks per pubkey
      retry-attempts: 3                           # Retries on delivery failure
      retry-backoff: [1s, 10s, 60s]               # Exponential backoff intervals
      deactivate-after-failures: 10               # Deactivate after N consecutive failures
      require-https: true                         # Only allow HTTPS webhook URLs

    # Metadata labels (local-only, not published to relays)
    labels:
      max-per-voucher: 10                         # Maximum labels per voucher
      max-key-length: 32                          # Maximum label key length
      max-value-length: 256                       # Maximum label value length

    # In-memory computed caches (built from nostrdb data)
    cache:
      summary-ttl: 30s                            # Portfolio summary cache TTL
      analytics-ttl: 5m                           # Analytics results cache TTL
      expiring-ttl: 60s                           # Expiring vouchers cache TTL

    export:
      max-vouchers: 10000                         # Maximum vouchers per export

    watch:
      max-duration: 30m                           # Maximum SSE connection duration
      heartbeat-interval: 30s                     # SSE heartbeat frequency
```

---

## Deployment

### Standalone

The client API module runs as an independent Spring Boot application, separate from the public web module. This allows independent scaling and security policies.

```
cashu-ledger-web        → :6060  (public, unauthenticated, relay-on-demand, storage optional)
cashu-ledger-client-api → :6061  (authenticated, storage-first, background sync)
```

**Startup sequence:**
1. Initialize nostrdb at configured path
2. Start sync engine (connects to relays, begins background polling)
3. Start HTTP server (begins accepting authenticated requests)

If nostrdb fails to initialize, the application **refuses to start** (fail-fast). Relay connectivity failures are non-fatal — the sync engine retries on the next cycle and the API serves stale-but-available data from nostrdb.

### Docker Compose

```yaml
services:
  ledger-web:
    image: cashu-ledger-web:latest
    ports:
      - "6060:6060"
    environment:
      LEDGER_WEB_STORAGE_ENABLED: "true"

  ledger-client-api:
    image: cashu-ledger-client-api:latest
    ports:
      - "6061:6061"
    volumes:
      - client-ndb-data:/data/ndb                 # Persistent nostrdb storage
    environment:
      LEDGER_CLIENT_STORAGE_PATH: /data/ndb
      LEDGER_CLIENT_API_RELAY_URLS: "wss://relay.imani.casa"
      LEDGER_CLIENT_SYNC_INTERVAL: "30s"

volumes:
  client-ndb-data:
```

### Storage Considerations

- **Persistence is mandatory.** The nostrdb volume must survive container restarts. Without persistent storage, every restart triggers a full initial sync for all clients, adding relay load and delay.
- **Separate storage paths.** The client API and public web module should use **independent** nostrdb instances. LMDB does not support concurrent writers from different processes. If both modules run on the same host, configure different `storage.path` values.
- **Backup.** The nostrdb directory can be backed up by copying the LMDB files while the application is stopped. Hot backup is not supported by LMDB.

---

## Implementation Phases

### Phase 1: Foundation and Storage

- New Maven module `cashu-ledger-client-api` with `cashu-ledger-core` dependency
- Mandatory nostrdb initialization (fail-fast if unavailable)
- `ClientSyncEngine`: background scheduler with priority queue (P0–P3 tiers)
  - Polls relays by author pubkey, `#sent_by`, and `#claimed_by` tags
  - Writes to nostrdb via `EventStore.store()`
  - Tiered inactivity timeouts (24h known / 1h unknown)
  - Anti-Sybil registration cap with priority-based eviction
- Client registration/deregistration lifecycle (pubkey tracking, voucher-count-based priority)
- NIP-98 authentication filter:
  - Schnorr signature verification
  - Event ID replay cache (nonce deduplication within timestamp tolerance window)
  - Pubkey extraction and sync engine registration
- `sent_by` tag support in `VoucherEventMapper` (with fallback to author pubkey)
- Security filter chain configuration
- `ClientVoucherService`: reads from nostrdb via `EventStore.findByAuthor()`, maps to domain objects
- Core DTOs

### Phase 2: Read Endpoints

- `GET /summary` — portfolio overview (computed from nostrdb, cached in memory with 30s TTL)
- `GET /vouchers` — list with filtering and pagination (nostrdb author index + in-memory filter/sort)
- `GET /vouchers/{id}` — detail with access control (nostrdb d-tag index)
- `GET /vouchers/{id}/history` — scoped history (nostrdb `findAllByVoucherId`)
- `GET /vouchers/{id}/tree` — scoped tree view with role-based visibility pruning (redact siblings for recipients)
- `GET /vouchers/{id}/verify` — scoped verification
- `GET /received` — received vouchers (requires `#claimed_by` sync subscription)
- `GET /unclaimed` — unclaimed vouchers (nostrdb author index, status filter)
- On-demand relay fallback for cache misses on single-voucher queries
- `GET /sync/status` — sync engine health and per-client sync state

### Phase 3: Analytics and Monitoring

- `GET /activity` — activity feed (nostrdb author query, in-memory sort by transition timestamp)
- `GET /expiring` — expiring voucher alerts (nostrdb author query, in-memory expiry filter)
- `GET /analytics/issuance` — issuance statistics (nostrdb author query, in-memory aggregation, 5m cache TTL)
- `GET /analytics/liabilities` — outstanding float and liability breakdown for issuers
- `GET /watch` — SSE stream (subscribe to sync engine's internal change events, no per-connection relay polling)
- Sync engine change detection: compare state versions before/after each sync cycle

### Phase 4: Write Operations and Export

- `POST /unclaimed/{id}/reclaim` — reclaim (requires mint integration)
- `POST /vouchers/{id}/revoke` — revoke
- `POST /vouchers/batch-revoke` — batch revocation (max 100 per request)
- `POST /unclaimed/batch-reclaim` — batch reclaim (max 50 per request)
- `GET /export` — JSON/CSV data export (stream from nostrdb, no full materialization in memory)
- `PUT /vouchers/{id}/metadata` — local-only voucher labels (stored in nostrdb, not published)

### Phase 5: Webhooks and Delegation

- `POST /webhooks` — register webhook for state change callbacks
- Webhook delivery engine with retry, backoff, and auto-deactivation
- NIP-26 delegation support: worker key registration, consolidated views for master keys
- `POST /delegations` — register delegated worker keys
- Sync engine expansion: track worker key vouchers under master key umbrella

### Phase 6: Hardening

- Rate limiting (per-pubkey, per-endpoint-category)
- Request logging and audit trail
- Sync engine monitoring: metrics for sync latency, event counts, registered clients, priority distribution
- Computed cache invalidation on sync engine state-change events
- Integration tests with mocked NIP-98 auth (including replay rejection) and pre-populated nostrdb
- Tree visibility tests: verify sibling redaction for recipient role
- E2E tests against staging relays
- API documentation (OpenAPI/Swagger)
- Storage health endpoint: nostrdb availability, event count, last sync timestamp per client

---

## Related Documentation

- [Architecture Overview](../explanation/architecture.md)
- [REST API Reference (Public)](../reference/rest-api.md)
- [Voucher Specification](../reference/voucher-specification.md)
- [State Machine Design](../explanation/state-machine.md)
- [Cashu Ledger Specification](cashu-ledger-specification.md)
- [NIP-98 HTTP Auth](https://github.com/nostr-protocol/nips/blob/master/98.md)
