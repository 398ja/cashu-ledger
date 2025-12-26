# Cashu Ledger Tool Specification

## Overview

The **cashu-ledger** tool is a command-line application for inspecting and analyzing vouchers stored on Nostr relays. It provides introspection capabilities for debugging, auditing, and investigating voucher hierarchies, status changes, and lineage tracking.

### Purpose

- **Debugging**: Trace issues with voucher splits, redemptions, or status updates
- **Auditing**: Verify voucher lineage and value conservation across splits
- **Investigation**: Analyze voucher trees to understand fund flows
- **Recovery**: Identify orphaned or inconsistent voucher states

---

## Voucher State Model

### States

Vouchers progress through a defined lifecycle represented by the following states:

| State | Description | Terminal |
|-------|-------------|----------|
| `ISSUED` | Voucher created and published to ledger, not yet imported by recipient | No |
| `CLAIMED` | Voucher imported into a wallet (ownership established) | No |
| `SPLIT` | Voucher subdivided into child vouchers (value redistributed) | Yes |
| `REDEEMED` | Voucher exchanged for Lightning payment or other settlement | Yes |
| `RECLAIMED` | Unclaimed voucher recovered by original sender | Yes |
| `REVOKED` | Voucher invalidated by issuer (e.g., fraud, error) | Yes |
| `EXPIRED` | Voucher past expiration date (automatic transition) | Yes |

### State Machine

```
                                    ┌───────────┐
                                    │  REVOKED  │
                                    └───────────┘
                                          ▲
                                          │ (issuer revokes)
                                          │
┌──────────┐      ┌──────────┐      ┌─────┴─────┐      ┌──────────┐
│  ISSUED  │─────▶│ CLAIMED  │─────▶│ REDEEMED  │      │ EXPIRED  │
└────┬─────┘      └────┬─────┘      └───────────┘      └──────────┘
     │                 │                                     ▲
     │                 │ (split)                             │
     │                 ▼                              (auto-expire)
     │           ┌──────────┐                               │
     │           │  SPLIT   │───────────────────────────────┘
     │           └──────────┘
     │                 │
     │                 │ creates
     │                 ▼
     │           ┌─────────────────────────────┐
     │           │ Child Vouchers:             │
     │           │  - Send portion → ISSUED    │
     │           │  - Keep portion → CLAIMED   │
     │           └─────────────────────────────┘
     │
     │ (sender reclaims unclaimed)
     ▼
┌──────────┐
│RECLAIMED │
└──────────┘
```

### State Transitions

| From | To | Trigger | Actor |
|------|----|---------|-------|
| `ISSUED` | `CLAIMED` | Recipient imports token into wallet | Recipient |
| `ISSUED` | `RECLAIMED` | Sender recovers unclaimed voucher | Sender |
| `ISSUED` | `EXPIRED` | Expiration timestamp reached | System |
| `CLAIMED` | `REDEEMED` | Voucher exchanged for Lightning/settlement | Owner |
| `CLAIMED` | `SPLIT` | Voucher subdivided for partial send | Owner |
| `CLAIMED` | `EXPIRED` | Expiration timestamp reached | System |
| `*` | `REVOKED` | Issuer invalidates voucher | Issuer |

### Split Operation Semantics

When a CLAIMED voucher is split:

1. **Parent voucher** transitions to `SPLIT` state
2. **Send portion** creates new child voucher in `ISSUED` state
3. **Keep portion** creates new child voucher in `CLAIMED` state (immediate ownership)

**Example:**
```
V (CLAIMED, €30, 3000 sats)
  ├── split operation by User A
  │
  ├── V1 (ISSUED, €10, 1000 sats)   → sent to User B
  │       └── B imports → CLAIMED
  │
  └── V' (CLAIMED, €20, 2000 sats)  → stays with User A
```

**Value Conservation Rule:**
```
V.faceValue == V1.faceValue + V'.faceValue
V.tokenAmount == V1.tokenAmount + V'.tokenAmount
```

### Claiming Semantics

The `CLAIMED` state represents **successful import** of a voucher into a wallet:

1. **Proof Swap**: Token proofs are swapped at the mint for fresh proofs
2. **Ownership Transfer**: The wallet now holds valid proofs for the voucher's value
3. **Ledger Update**: Voucher state published to Nostr relay as `CLAIMED`

**Important**: The claiming process is atomic with the proof swap. If the swap fails (proofs already spent), the claim fails and state remains `ISSUED`.

### Unclaimed Voucher Recovery

Vouchers in `ISSUED` state that haven't been claimed can be recovered by the sender:

**Prerequisites:**
1. Voucher state is `ISSUED` (not yet claimed)
2. Original token proofs are still valid at mint (not spent)
3. Sender has the original token data

**Recovery Process:**
1. Sender initiates reclaim from their "Pending Sends" view
2. System checks proof status at mint
3. If proofs valid: swap to sender's wallet, create new voucher as `CLAIMED`
4. Original voucher state → `RECLAIMED`
5. If proofs invalid: claim failed elsewhere, show error

**Race Condition Handling:**
If recipient claims while sender attempts reclaim, the first party to complete the mint swap wins. The other receives a "proof already used" error.

### Ledger Event Updates

When voucher state changes, a new Nostr event is published:

```json
{
  "kind": 30078,
  "tags": [
    ["d", "v-1766748473969"],
    ["status", "claimed"],
    ["claimed_at", "1735225200"],
    ["claimed_by", "<recipient_pubkey>"],
    ["previous_status", "issued"]
  ]
}
```

**New Tags for State Tracking:**

| Tag | Description | States |
|-----|-------------|--------|
| `claimed_at` | Unix timestamp of claim | `CLAIMED` |
| `claimed_by` | Recipient pubkey (hex) | `CLAIMED` |
| `split_at` | Unix timestamp of split | `SPLIT` |
| `split_into` | Child voucher IDs | `SPLIT` |
| `reclaimed_at` | Unix timestamp of reclaim | `RECLAIMED` |
| `redeemed_at` | Unix timestamp of redemption | `REDEEMED` |
| `sent_to` | Intended recipient pubkey | `ISSUED` (when sent via DM) |

---

## Architecture

This is a **standalone application** (separate from cashu-client) with its own repository and release cycle.

### Repository

```
cashu-ledger/                    # Independent repository
├── cashu-ledger-cli/            # CLI interface (Picocli)
│   ├── src/
│   │   ├── main/java/
│   │   └── main/resources/
│   └── pom.xml
├── cashu-ledger-core/           # Core domain and services
│   ├── src/
│   │   ├── main/java/
│   │   └── test/java/
│   └── pom.xml
├── pom.xml                      # Parent POM
├── README.md
├── CHANGELOG.md
└── LICENSE
```

### Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `nostr-java` | 1.2.0+ | Nostr relay connectivity and event handling |
| `cashu-voucher` | 0.3.6+ | Voucher domain model and verification |
| `picocli` | 4.7+ | Command-line argument parsing |
| `jackson` | 2.15+ | JSON output formatting |
| `slf4j` | 2.0+ | Logging facade |
| `logback` | 1.4+ | Logging implementation |

---

## Command-Line Interface

### Synopsis

```bash
cashu-ledger [OPTIONS] <command> [ARGS]
```

### Global Options

| Option | Description | Default |
|--------|-------------|---------|
| `-r, --relay <URL>` | Nostr relay URL (repeatable) | `wss://relay.damus.io` |
| `-o, --output <format>` | Output format: `text`, `json`, `tree` | `text` |
| `-v, --verbose` | Enable verbose logging | `false` |
| `--timeout <seconds>` | Connection timeout per relay | `30` |

### Commands

#### 1. `inspect` - Inspect a Single Voucher

Fetch and display detailed information about a specific voucher.

```bash
cashu-ledger inspect <voucher-id>
cashu-ledger -r wss://relay.imani.casa inspect v-1766748473969
```

**Output Fields:**
- Voucher ID
- Issuer ID and public key
- Face value (original and current)
- Token amount (original and current)
- Unit and decimals
- Backing strategy
- Issuance ratio
- Status (issued, claimed, split, redeemed, reclaimed, revoked, expired)
- Issued/Expires timestamps
- Memo
- Merchant metadata
- Parent contributions (if any)
- Event metadata (created_at, pubkey, event ID)

**Example Output (text format):**
```
Voucher: v-1766748473969
=======================
Status:           issued
Issuer ID:        merchant-001
Issuer PubKey:    a1b2c3d4...

Value:
  Face Value:     €10.00 (original: €10.00)
  Token Amount:   1000 sats (original: 1000 sats)
  Issuance Ratio: 1.0

Backing:
  Strategy:       PROPORTIONAL
  Unit:           EUR
  Decimals:       2

Lifecycle:
  Issued At:      2025-12-26T10:30:00Z
  Expires At:     2026-01-26T10:30:00Z

Parents: none (root voucher)

Event Metadata:
  Event ID:       abc123...
  Created At:     2025-12-26T10:30:05Z
  Relay:          wss://relay.imani.casa
```

---

#### 2. `tree` - Display Voucher Hierarchy

Trace the complete hierarchy of a voucher (ancestors and descendants).

```bash
cashu-ledger tree <voucher-id>
cashu-ledger tree --depth 5 --direction both v-1766748473969
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--depth <n>` | Maximum depth to traverse | `10` |
| `--direction <dir>` | `up` (ancestors), `down` (descendants), `both` | `both` |
| `--show-values` | Display face value and token amounts | `true` |
| `--show-status` | Display voucher status | `true` |

**Output Example (tree format):**
```
Voucher Hierarchy for v-1766748473969
=====================================

◉ v-1766748470000 [€50.00, 5000 sats] REDEEMED (root)
├── ◉ v-1766748471000 [€20.00, 2000 sats] REDEEMED
│   └── ◉ v-1766748473000 [€20.00, 2000 sats] REDEEMED
└── ◉ v-1766748472000 [€30.00, 3000 sats] ISSUED
    ├── ◉ v-1766748473969 [€10.00, 1000 sats] ISSUED ← (target)
    └── ◉ v-1766748474000 [€20.00, 2000 sats] ISSUED

Legend:
  ◉ = voucher node
  ← = target voucher
  [face value, token amount] STATUS
```

**JSON Output:**
```json
{
  "target": "v-1766748473969",
  "root": "v-1766748470000",
  "nodes": [
    {
      "voucherId": "v-1766748470000",
      "faceValue": 5000,
      "tokenAmount": 5000,
      "status": "redeemed",
      "children": ["v-1766748471000", "v-1766748472000"],
      "parents": []
    }
  ],
  "depth": 3,
  "totalNodes": 5
}
```

---

#### 3. `history` - View Status Change History

Fetch all events for a voucher to reconstruct its status history.

```bash
cashu-ledger history <voucher-id>
cashu-ledger history --since 2025-12-01 v-1766748473969
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--since <date>` | Filter events after date (ISO-8601) | none |
| `--until <date>` | Filter events before date (ISO-8601) | none |
| `--limit <n>` | Maximum events to fetch | `100` |

**Output Example:**
```
Status History for v-1766748473969
===================================

 #  Timestamp                Status     Event ID       Relay
 1  2025-12-26T10:30:00Z    issued     abc123...      relay.imani.casa
 2  2025-12-26T14:15:30Z    redeemed   def456...      relay.imani.casa

Total: 2 status changes
```

---

#### 4. `search` - Search Vouchers by Criteria

Find vouchers matching specific criteria across relays.

```bash
cashu-ledger search --issuer merchant-001
cashu-ledger search --status issued --since 2025-12-01
cashu-ledger search --min-value 1000 --unit EUR
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--issuer <id>` | Filter by issuer ID | |
| `--status <status>` | Filter by status (issued, claimed, split, redeemed, reclaimed, revoked, expired) | |
| `--since <date>` | Issued after date | |
| `--until <date>` | Issued before date | |
| `--min-value <amount>` | Minimum face value (in minor units) | |
| `--max-value <amount>` | Maximum face value (in minor units) | |
| `--unit <currency>` | Filter by currency unit | |
| `--has-parents` | Only vouchers with parent contributions | |
| `--is-root` | Only root vouchers (no parents) | |
| `--unclaimed` | Only ISSUED vouchers sent but not yet claimed | |
| `--sent-to <pubkey>` | Filter by intended recipient pubkey | |
| `--sent-by <pubkey>` | Filter by sender pubkey | |
| `--limit <n>` | Maximum results | `50` |

**Output Example:**
```
Search Results (5 vouchers found)
==================================

 ID                   Face Value   Status    Issuer         Issued At
 v-1766748473969     €10.00       issued    merchant-001   2025-12-26T10:30:00Z
 v-1766748474000     €20.00       issued    merchant-001   2025-12-26T10:35:00Z
 v-1766748475000     €5.00        redeemed  merchant-001   2025-12-25T09:00:00Z
 ...
```

---

#### 5. `verify` - Verify Voucher Integrity

Verify the cryptographic integrity and consistency of a voucher.

```bash
cashu-ledger verify <voucher-id>
cashu-ledger verify --check-hierarchy v-1766748473969
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--check-signature` | Verify issuer Ed25519 signature | `true` |
| `--check-hierarchy` | Verify value conservation across splits | `false` |
| `--check-expiry` | Flag expired vouchers | `true` |

**Verification Checks:**
1. **Signature Verification**: Ed25519 signature from issuer
2. **Value Conservation**: Parent contributions sum equals child values
3. **Issuance Ratio Consistency**: Ratio preserved across splits
4. **Status Consistency**: No invalid status transitions
5. **Expiry Check**: Flag vouchers past expiration

**Output Example:**
```
Verification Report for v-1766748473969
========================================

✓ Signature:       VALID (issuer: merchant-001)
✓ Value:           CONSERVED (10.00 EUR from parents)
✓ Issuance Ratio:  CONSISTENT (1.0)
✓ Status:          VALID (issued)
✓ Expiry:          VALID (expires 2026-01-26)

Overall: PASSED (5/5 checks)
```

---

#### 6. `diff` - Compare Two Vouchers

Compare two vouchers to understand their relationship or differences.

```bash
cashu-ledger diff <voucher-id-1> <voucher-id-2>
```

**Output Example:**
```
Comparison: v-1766748473969 vs v-1766748474000
==============================================

                    v-1766748473969    v-1766748474000
Face Value:         €10.00             €20.00
Token Amount:       1000 sats          2000 sats
Status:             issued             issued
Issuer:             merchant-001       merchant-001

Relationship: SIBLINGS (share parent v-1766748472000)
Combined Value: €30.00 (3000 sats)
```

---

#### 7. `export` - Export Voucher Data

Export voucher data for external analysis.

```bash
cashu-ledger export <voucher-id> --format json > voucher.json
cashu-ledger export --tree <voucher-id> --format csv > hierarchy.csv
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--format <fmt>` | Export format: `json`, `csv`, `cbor` | `json` |
| `--tree` | Include full hierarchy | `false` |
| `--include-events` | Include raw Nostr events | `false` |

---

#### 8. `watch` - Real-time Monitoring

Subscribe to real-time updates for a voucher or issuer.

```bash
cashu-ledger watch <voucher-id>
cashu-ledger watch --issuer merchant-001
```

**Output (streaming):**
```
Watching v-1766748473969...
[2025-12-26T15:00:00Z] Status changed: issued → redeemed
[2025-12-26T15:00:01Z] Event received from relay.imani.casa
^C Stopped watching.
```

---

#### 9. `unclaimed` - Manage Unclaimed Vouchers

List and manage vouchers that were sent but never claimed by recipients.

```bash
cashu-ledger unclaimed --sent-by <my-pubkey>
cashu-ledger unclaimed --check-status v-1766748473969
cashu-ledger unclaimed --reclaim v-1766748473969
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--sent-by <pubkey>` | Filter by sender pubkey (required for list) | |
| `--older-than <duration>` | Only show unclaimed older than duration (e.g., 24h, 7d) | `0` |
| `--check-status` | Check if proofs are still valid at mint | `false` |
| `--reclaim` | Attempt to reclaim the voucher (requires token file) | `false` |
| `--token-file <path>` | Path to original token file (for reclaim) | |

**Subcommands:**

**List unclaimed vouchers:**
```bash
cashu-ledger unclaimed --sent-by npub1abc...
```

**Output:**
```
Unclaimed Vouchers (3 found)
============================

 ID                   Face Value   Sent To              Sent At               Age
 v-1766748473969     €10.00       npub1xyz...          2025-12-24T10:00:00Z  2d 5h
 v-1766748474000     €25.00       npub1def...          2025-12-25T14:30:00Z  1d 0h
 v-1766748475000     €5.00        npub1ghi...          2025-12-26T09:00:00Z  6h

Total unclaimed value: €40.00 (4000 sats)

Tip: Use --check-status <id> to verify if proofs are still reclaimable.
```

**Check proof status:**
```bash
cashu-ledger unclaimed --check-status v-1766748473969
```

**Output:**
```
Proof Status Check for v-1766748473969
======================================

Voucher:        v-1766748473969
Face Value:     €10.00
Sent To:        npub1xyz...
Sent At:        2025-12-24T10:00:00Z

Proof Status:
  Proof 1 (500 sats): ✓ UNSPENT - reclaimable
  Proof 2 (500 sats): ✓ UNSPENT - reclaimable

Result: RECLAIMABLE
All proofs are still valid at the mint. You can reclaim this voucher.
```

**Reclaim a voucher:**
```bash
cashu-ledger unclaimed --reclaim v-1766748473969 --token-file ./voucher.token
```

**Output:**
```
Reclaiming v-1766748473969...

Step 1/3: Verifying proof status... ✓ All proofs unspent
Step 2/3: Swapping proofs at mint... ✓ Swap successful
Step 3/3: Updating ledger status... ✓ Status → RECLAIMED

Reclaim Successful!
  Original Voucher: v-1766748473969 → RECLAIMED
  New Voucher:      v-1766748500000 → CLAIMED (in your wallet)
  Amount Recovered: €10.00 (1000 sats)

The original token file is now invalid. The new voucher is in your wallet.
```

**Failed reclaim (already claimed):**
```
Reclaiming v-1766748473969...

Step 1/3: Verifying proof status...

⚠ RECLAIM FAILED

Proof Status:
  Proof 1 (500 sats): ✗ SPENT
  Proof 2 (500 sats): ✗ SPENT

The recipient has already claimed this voucher. The proofs have been
spent at the mint. Check the ledger for the current status:

  cashu-ledger inspect v-1766748473969
```

---

## Core Domain Model

### VoucherNode

Represents a voucher in the hierarchy graph.

```java
public record VoucherNode(
    String voucherId,
    String issuerId,
    String issuerPublicKey,
    long faceValue,
    int faceDecimals,
    long tokenAmount,
    long originalFaceValue,
    long originalTokenAmount,
    String unit,
    BackingStrategy backingStrategy,
    BigDecimal issuanceRatio,
    String status,
    Instant issuedAt,
    Instant expiresAt,
    String memo,
    Map<String, Object> merchantMetadata,
    List<ParentContribution> parentContributions,
    NostrEventMetadata eventMetadata
) {}
```

### VoucherTree

Represents the complete hierarchy.

```java
public record VoucherTree(
    VoucherNode root,
    VoucherNode target,
    Map<String, VoucherNode> nodes,
    Map<String, List<String>> childrenMap,
    int depth,
    int totalNodes
) {
    // Returns all ancestor nodes up to the root
    public List<VoucherNode> ancestors(String voucherId) { /* ... */ }

    // Returns all descendant nodes down to leaves
    public List<VoucherNode> descendants(String voucherId) { /* ... */ }

    // Returns sibling nodes (same parent)
    public List<VoucherNode> siblings(String voucherId) { /* ... */ }

    // True if voucher has no parents
    public boolean isRoot(String voucherId) { /* ... */ }

    // True if voucher has no children
    public boolean isLeaf(String voucherId) { /* ... */ }
}
```

### NostrEventMetadata

Metadata from the Nostr event.

```java
public record NostrEventMetadata(
    String eventId,
    String pubkey,
    Instant createdAt,
    String relay,
    int kind,
    List<List<String>> tags
) {}
```

---

## Services

### VoucherLedgerService

Core service for fetching and analyzing vouchers from relays.

```java
public interface VoucherLedgerService {
    /**
     * Fetch a single voucher by ID from configured relays.
     */
    Optional<VoucherNode> fetchVoucher(String voucherId);

    /**
     * Build the complete hierarchy for a voucher.
     */
    VoucherTree buildTree(String voucherId, int maxDepth, TraversalDirection direction);

    /**
     * Fetch status change history for a voucher.
     */
    List<StatusChange> fetchHistory(String voucherId, Instant since, Instant until);

    /**
     * Search vouchers by criteria.
     */
    List<VoucherNode> search(VoucherSearchCriteria criteria);

    /**
     * Verify voucher integrity.
     */
    VerificationReport verify(String voucherId, VerificationOptions options);

    /**
     * Subscribe to real-time updates.
     */
    Subscription watch(String voucherId, Consumer<VoucherUpdate> callback);
}
```

### RelayConnectionManager

Manages connections to multiple Nostr relays.

```java
public interface RelayConnectionManager {
    /**
     * Connect to configured relays.
     */
    void connect(List<String> relayUrls, Duration timeout);

    /**
     * Query relays for events matching filter.
     */
    List<NostrEvent> query(NostrFilter filter);

    /**
     * Subscribe to events matching filter.
     */
    Subscription subscribe(NostrFilter filter, Consumer<NostrEvent> callback);

    /**
     * Disconnect from all relays.
     */
    void disconnect();
}
```

---

## Nostr Query Filters

### Fetching a Voucher by ID

```json
{
  "kinds": [30078],
  "#d": ["v-1766748473969"]
}
```

### Searching by Issuer

```json
{
  "kinds": [30078],
  "#issuer_id": ["merchant-001"],
  "since": 1735171200
}
```

### Fetching by Status

```json
{
  "kinds": [30078],
  "#status": ["issued"]
}
```

---

## Hierarchy Traversal Algorithm

### Building the Voucher Tree

```
function buildTree(voucherId, maxDepth, direction):
    target = fetchVoucher(voucherId)
    if target is null:
        return empty tree

    nodes = {voucherId: target}

    if direction in [UP, BOTH]:
        traverseUp(target, nodes, maxDepth)

    if direction in [DOWN, BOTH]:
        traverseDown(target, nodes, maxDepth)

    return constructTree(nodes, target)

function traverseUp(node, nodes, remainingDepth):
    if remainingDepth <= 0:
        return

    for contribution in node.parentContributions:
        parentId = contribution.parentVoucherId
        if parentId not in nodes:
            parent = fetchVoucher(parentId)
            if parent is not null:
                nodes[parentId] = parent
                traverseUp(parent, nodes, remainingDepth - 1)

function traverseDown(node, nodes, remainingDepth):
    if remainingDepth <= 0:
        return

    # Query for vouchers that reference this node as parent
    children = searchByParent(node.voucherId)

    for child in children:
        if child.voucherId not in nodes:
            nodes[child.voucherId] = child
            traverseDown(child, nodes, remainingDepth - 1)
```

### Finding Children

Since children reference parents via `parentContributions`, we search for vouchers containing the parent ID:

```json
{
  "kinds": [30078],
  "search": "parent_voucher_id:v-1766748470000"
}
```

**Note**: This requires either:
1. A custom tag `#parent` added to voucher events
2. Full-text search support on relays
3. Client-side filtering after broader queries

**Recommendation**: Add a `#parent` tag to voucher events during issuance to enable efficient child lookups.

---

## Value Conservation Verification

Verify that splits preserve total value:

```
function verifyValueConservation(node):
    if node.parentContributions is empty:
        return VALID  # Root voucher

    totalContributedFace = sum(c.contributedFaceValue for c in node.parentContributions)
    totalContributedSats = sum(c.contributedSats for c in node.parentContributions)

    if node.originalFaceValue != totalContributedFace:
        return INVALID("Face value mismatch")

    if node.originalTokenAmount != totalContributedSats:
        return INVALID("Token amount mismatch")

    # Verify issuance ratio preserved
    expectedRatio = totalContributedFace / totalContributedSats
    if abs(node.issuanceRatio - expectedRatio) > EPSILON:
        return INVALID("Issuance ratio drift")

    return VALID
```

---

## Configuration

### Application Properties

```properties
# ledger.properties
ledger.default-relays=wss://relay.damus.io,wss://nos.lol
ledger.connection-timeout=30s
ledger.query-timeout=60s
ledger.max-tree-depth=20
ledger.cache-ttl=5m
ledger.output-format=text
```

### Relay Configuration

```yaml
# ledger-relays.yaml
relays:
  - url: wss://relay.imani.casa
    priority: 1
    trusted: true
  - url: wss://relay.damus.io
    priority: 2
    trusted: false
```

---

## Error Handling

### Error Categories

| Category | Example | Action |
|----------|---------|--------|
| `RELAY_UNAVAILABLE` | Connection timeout | Try next relay |
| `VOUCHER_NOT_FOUND` | No event for ID | Report not found |
| `INVALID_EVENT` | Malformed event data | Skip, log warning |
| `SIGNATURE_INVALID` | Bad Ed25519 sig | Flag in output |
| `HIERARCHY_INCOMPLETE` | Missing parent | Flag, continue |

### Graceful Degradation

- If one relay fails, continue with others
- If parent voucher not found, mark as "orphaned" but continue
- If signature verification fails, still display data with warning

---

## Use Cases

### 1. Debugging a Failed Redemption

```bash
# Fetch voucher details
cashu-ledger inspect v-1766748473969

# Check if already redeemed
cashu-ledger history v-1766748473969

# Verify signature
cashu-ledger verify v-1766748473969
```

### 2. Auditing Merchant Vouchers

```bash
# List all vouchers for a merchant
cashu-ledger search --issuer merchant-001 --since 2025-12-01

# Export for analysis
cashu-ledger search --issuer merchant-001 --format csv > merchant_vouchers.csv
```

### 3. Investigating a Split Issue

```bash
# View the full tree
cashu-ledger tree v-1766748473969 --show-values

# Verify value conservation
cashu-ledger verify --check-hierarchy v-1766748473969
```

### 4. Monitoring Live Voucher Activity

```bash
# Watch for status changes
cashu-ledger watch --issuer merchant-001
```

### 5. Recovery Investigation

```bash
# Find orphaned vouchers (have parents but parents missing)
cashu-ledger search --has-parents --format json | \
  jq '.[] | select(.parentContributions[].orphaned == true)'
```

### 6. Recovering Unclaimed Vouchers

```bash
# List all unclaimed vouchers sent by you
cashu-ledger unclaimed --sent-by $(cashu-wallet pubkey)

# Check if a specific voucher can be reclaimed
cashu-ledger unclaimed --check-status v-1766748473969

# Reclaim an unclaimed voucher
cashu-ledger unclaimed --reclaim v-1766748473969 --token-file ./pending/v-1766748473969.token
```

### 7. Tracking Claim Status

```bash
# Watch for when a sent voucher gets claimed
cashu-ledger watch v-1766748473969

# Search for all claimed vouchers by recipient
cashu-ledger search --status claimed --sent-to npub1recipient...
```

---

## Wallet UI Integration: Pending Sends

The wallet application should provide a dedicated "Pending Sends" view for managing unclaimed vouchers.

### UI Mockup

```
┌─────────────────────────────────────────────────────────────────┐
│  Pending Sends                                         [Refresh]│
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ 🕐 €10.00 EUR                                    2 days   │  │
│  │    To: @alice (npub1abc...)                              │  │
│  │    Sent: Dec 24, 2025 10:00 AM                           │  │
│  │                                                          │  │
│  │    [Check Status]  [Remind]  [Reclaim]                   │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ 🕐 €25.00 EUR                                    1 day    │  │
│  │    To: @bob (npub1def...)                                │  │
│  │    Sent: Dec 25, 2025 2:30 PM                            │  │
│  │                                                          │  │
│  │    [Check Status]  [Remind]  [Reclaim]                   │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━│
│  Total Pending: €35.00 (3500 sats)                 2 vouchers  │
└─────────────────────────────────────────────────────────────────┘
```

### Actions

**Check Status**: Query mint to determine if proofs are still valid.
- Shows "Reclaimable" if proofs unspent
- Shows "Claimed" if proofs were spent (recipient claimed)
- Shows "Error" if mint unavailable

**Remind**: Send another DM to recipient with the same voucher token.
- Useful if recipient missed or lost the original message
- Should include original voucher metadata

**Reclaim**: Recover the voucher back to sender's wallet.
- Only enabled after status check shows "Reclaimable"
- Swaps proofs at mint, creates new voucher as CLAIMED
- Updates original voucher status to RECLAIMED on ledger

### Implementation Notes

1. **Local Storage**: Store sent voucher tokens locally until confirmed claimed
   - File: `~/.cashu/pending_sends/<voucher_id>.json`
   - Include: token, recipient_pubkey, sent_at, voucher_metadata

2. **Background Sync**: Periodically check ledger for status updates
   - Watch for `CLAIMED` status on sent vouchers
   - Move claimed vouchers to history, delete local token file

3. **Reclaim Timeout**: Consider showing reclaim option only after delay
   - Default: 24 hours (configurable)
   - Prevents race conditions with slow recipients

4. **Notification**: When recipient claims, show notification
   - "Alice claimed your €10.00 voucher"
   - Move from Pending to History

### Data Model

```typescript
interface PendingSend {
  voucherId: string;
  token: string;              // Original cashuA/cashuB token
  recipientPubkey: string;    // npub or hex
  recipientAlias?: string;    // NIP-05 or display name
  sentAt: number;             // Unix timestamp
  faceValue: number;
  faceUnit: string;
  tokenAmount: number;        // Backing sats
  dmEventId?: string;         // Nostr DM event ID
  status: 'pending' | 'checking' | 'reclaimable' | 'claimed' | 'reclaimed';
  lastChecked?: number;       // Last mint query timestamp
}
```

---

## Implementation Roadmap

### Phase 1: Core Functionality

| ID | Task | Size | Depends On | Project | Status | Commit |
|----|------|------|------------|---------|--------|--------|
| 1.1 | Project setup (Maven module structure) | M | - | cashu-ledger | ✅ Done | _uncommitted (workspace)_ |
| 1.2 | Nostr relay connection manager | L | 1.1 | cashu-ledger | ✅ Done | _uncommitted (workspace)_ |
| 1.3 | Single voucher fetch (`inspect` command) | M | 1.2 | cashu-ledger | ✅ Done | _uncommitted (workspace)_ |
| 1.4 | Basic text output formatting | S | 1.3 | cashu-ledger | ✅ Done | _uncommitted (workspace)_ |

### Phase 2: State Model Implementation

| ID | Task | Size | Depends On | Project | Status | Commit |
|----|------|------|------------|---------|--------|--------|
| 2.1 | Implement full state machine (ISSUED → CLAIMED → REDEEMED) | L | 1.3 | cashu-ledger | Pending | - |
| 2.2 | Add SPLIT state for subdivided vouchers | M | 2.1 | cashu-ledger | Pending | - |
| 2.3 | Add RECLAIMED state for recovered vouchers | M | 2.1 | cashu-ledger | Pending | - |
| 2.4 | State transition validation | M | 2.1, 2.2, 2.3 | cashu-ledger | Pending | - |
| 2.5 | Ledger event publishing for state changes | L | 2.4 | cashu-ledger | Pending | - |

### Phase 3: Hierarchy Support

| ID | Task | Size | Depends On | Project | Status | Commit |
|----|------|------|------------|---------|--------|--------|
| 3.1 | Parent traversal algorithm | M | 1.3 | cashu-ledger | ✅ Done | _uncommitted (workspace)_ |
| 3.2 | Child discovery via Nostr queries | M | 1.3 | cashu-ledger | ✅ Done | _uncommitted (workspace)_ |
| 3.3 | Tree building algorithm | L | 3.1, 3.2 | cashu-ledger | ✅ Done | _uncommitted (workspace)_ |
| 3.4 | Tree visualization (`tree` command) | M | 3.3, 1.4 | cashu-ledger | ✅ Done | _uncommitted (workspace)_ |
| 3.5 | Split operation tracking | M | 3.3, 2.2 | cashu-ledger | Pending | - |

### Phase 4: Search and History

| ID | Task | Size | Depends On | Project | Status | Commit |
|----|------|------|------------|---------|--------|--------|
| 4.1 | Search by criteria (`search` command) | L | 1.2 | cashu-ledger | Pending | - |
| 4.2 | Status history tracking (`history` command) | M | 2.5 | cashu-ledger | Pending | - |
| 4.3 | JSON/CSV export (`export` command) | M | 4.1 | cashu-ledger | Pending | - |
| 4.4 | `--unclaimed` filter support | S | 4.1, 2.3 | cashu-ledger | Pending | - |

### Phase 5: Unclaimed Voucher Management

| ID | Task | Size | Depends On | Project | Status | Commit |
|----|------|------|------------|---------|--------|--------|
| 5.1 | `unclaimed` command implementation | M | 4.4 | cashu-ledger | Pending | - |
| 5.2 | Proof status checking at mint | L | 5.1 | cashu-ledger | Pending | - |
| 5.3 | Reclaim workflow | L | 5.2, 2.3 | cashu-ledger | Pending | - |
| 5.4 | Pending sends local storage | M | 5.1 | cashu-client | Pending | - |
| 5.5 | Wallet UI "Pending Sends" view | L | 5.4, 5.2 | imani-apps | Pending | - |

### Phase 6: Verification

| ID | Task | Size | Depends On | Project | Status | Commit |
|----|------|------|------------|---------|--------|--------|
| 6.1 | Signature verification (`verify` command) | M | 1.3 | cashu-ledger | Pending | - |
| 6.2 | Value conservation checks | M | 3.3 | cashu-ledger | Pending | - |
| 6.3 | Hierarchy integrity validation | M | 3.3, 6.2 | cashu-ledger | Pending | - |
| 6.4 | State transition audit | M | 2.4, 4.2 | cashu-ledger | Pending | - |

### Phase 7: Real-time and Polish

| ID | Task | Size | Depends On | Project | Status | Commit |
|----|------|------|------------|---------|--------|--------|
| 7.1 | Watch command (subscriptions) | L | 1.2 | cashu-ledger | Pending | - |
| 7.2 | Diff command | M | 1.3 | cashu-ledger | Pending | - |
| 7.3 | Performance optimization (caching, batch queries) | M | 4.1, 3.3 | cashu-ledger | Pending | - |
| 7.4 | CLI documentation and help text | S | All | cashu-ledger | Pending | - |
| 7.5 | User guide and examples | M | 7.4 | cashu-ledger | Pending | - |

### Task Size Legend

| Size | Description | Estimated Effort |
|------|-------------|------------------|
| S | Small - Straightforward, single file change | < 1 day |
| M | Medium - Multiple files, moderate complexity | 1-3 days |
| L | Large - Complex feature, multiple components | 3-5 days |
| XL | Extra Large - Major feature, architectural changes | 1+ week |

### Status Legend

| Status | Description |
|--------|-------------|
| Pending | Not started |
| In Progress | Currently being worked on |
| Review | Complete, awaiting review |
| Done | Merged and deployed |
| Blocked | Waiting on external dependency |

---

## Testing Strategy

### Unit Tests
- VoucherNode parsing from Nostr events
- Tree building algorithm
- Value conservation verification
- Output formatting
- State machine transition validation
- State transition event generation

### Integration Tests
- Relay connectivity (testcontainers)
- Multi-relay queries
- Subscription handling
- State change event publishing
- Proof status checking at mint

### E2E Tests
- Full CLI command execution
- Hierarchical voucher scenarios
- Export/import round-trips
- Complete claim lifecycle (ISSUED → CLAIMED → REDEEMED)
- Split operation with child voucher creation
- Unclaimed voucher reclaim workflow
- Race condition handling (concurrent claim/reclaim)

---

## Security Considerations

1. **No Private Keys**: Tool is read-only, no signing required
2. **Relay Trust**: Display relay source for each piece of data
3. **Signature Verification**: Always verify issuer signatures
4. **Input Validation**: Validate voucher IDs, relay URLs
5. **No Token Handling**: Never process or display actual Cashu tokens

---

## Appendix: Nostr Event Structure

### Kind 30078 Voucher Event

```json
{
  "id": "abc123...",
  "pubkey": "issuer_pubkey_hex",
  "created_at": 1735212600,
  "kind": 30078,
  "tags": [
    ["d", "v-1766748473969"],
    ["status", "issued"],
    ["issuer_id", "merchant-001"],
    ["face_value", "1000"],
    ["unit", "EUR"],
    ["decimals", "2"],
    ["token_amount", "1000"],
    ["backing_strategy", "PROPORTIONAL"],
    ["issuance_ratio", "1.0"],
    ["expires_at", "1737804600"],
    ["parent", "v-1766748472000", "1000", "1000"]
  ],
  "content": "<encrypted or empty>",
  "sig": "event_signature"
}
```

### Tag Definitions

| Tag | Description | Example |
|-----|-------------|---------|
| `d` | Voucher ID (NIP-33 identifier) | `v-1766748473969` |
| `status` | Current status | `issued`, `claimed`, `split`, `redeemed`, `reclaimed`, `revoked`, `expired` |
| `issuer_id` | Merchant identifier | `merchant-001` |
| `face_value` | Face value in minor units | `1000` (€10.00) |
| `unit` | Currency unit | `EUR`, `sat` |
| `decimals` | Decimal places | `2` |
| `token_amount` | Backing sats | `1000` |
| `backing_strategy` | Strategy enum | `PROPORTIONAL` |
| `issuance_ratio` | Face per sat | `1.0` |
| `expires_at` | Unix timestamp | `1737804600` |
| `parent` | Parent contribution | `[id, sats, face]` |
| `sent_to` | Intended recipient pubkey | `<pubkey_hex>` |
| `sent_at` | Unix timestamp when sent | `1735225200` |
| `claimed_by` | Recipient who claimed | `<pubkey_hex>` |
| `claimed_at` | Unix timestamp of claim | `1735225200` |
| `split_into` | Child voucher IDs | `v-123,v-124` |
| `split_at` | Unix timestamp of split | `1735225200` |
| `reclaimed_at` | Unix timestamp of reclaim | `1735225200` |
| `redeemed_at` | Unix timestamp of redemption | `1735225200` |
| `previous_status` | Status before transition | `issued` |

---

## Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 0.2.0 | 2025-12-26 | Claude | Added CLAIMED state, SPLIT state, RECLAIMED state; unclaimed voucher recovery; new `unclaimed` command |
| 0.1.0 | 2025-12-26 | Claude | Initial specification |
