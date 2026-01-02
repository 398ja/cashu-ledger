# CLI Commands Reference

Complete reference for cashu-ledger CLI commands.

## Synopsis

```bash
cashu-ledger [GLOBAL OPTIONS] <command> [COMMAND OPTIONS] [ARGS]
```

## Global Options

| Option | Description | Default |
|--------|-------------|---------|
| `-r, --relay <URL>` | Nostr relay URL (repeatable for multiple relays) | `wss://relay.imani.casa` |
| `-o, --output <format>` | Output format: `text`, `json`, `tree` | `text` |
| `-v, --verbose` | Enable verbose logging | `false` |
| `--timeout <seconds>` | Connection timeout per relay | `30` |
| `--storage-path <path>` | Path to local event store database | `~/.cashu-ledger/ndb` |
| `--no-cache` | Disable local caching (relay-only mode) | `false` |
| `--help` | Show help message | |
| `--version` | Show version information | |

---

## Commands

### inspect

Fetch and display detailed information about a specific voucher.

```bash
cashu-ledger inspect <voucher-id>
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `voucher-id` | The voucher identifier (e.g., `v-1766748473969`) | Yes |

**Examples:**

```bash
# Basic inspection
cashu-ledger inspect v-1766748473969

# Using a specific relay
cashu-ledger -r wss://nos.lol inspect v-1766748473969

# JSON output
cashu-ledger -o json inspect v-1766748473969
```

**Output Fields:**

- Voucher ID
- Status (issued, claimed, split, redeemed, reclaimed, revoked, expired)
- Issuer ID and public key
- Face value (current and original)
- Token amount (current and original)
- Unit and decimals
- Backing strategy
- Issuance ratio
- Issued/Expires timestamps
- Memo
- Parent contributions (if any)
- Event metadata (event ID, pubkey, relay)

---

### tree

Display the complete hierarchy of a voucher (ancestors and descendants).

```bash
cashu-ledger tree <voucher-id> [OPTIONS]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `voucher-id` | The voucher identifier | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--depth <n>` | Maximum depth to traverse | `10` |
| `--direction <dir>` | Direction: `up`, `down`, `both` | `both` |
| `--show-values` | Display face value and token amounts | `true` |
| `--show-status` | Display voucher status | `true` |

**Examples:**

```bash
# Full tree in both directions
cashu-ledger tree v-1766748473969

# Ancestors only (up to 5 levels)
cashu-ledger tree --direction up --depth 5 v-1766748473969

# Descendants only
cashu-ledger tree --direction down v-1766748473969
```

---

### history

View the status change history for a voucher.

```bash
cashu-ledger history <voucher-id> [OPTIONS]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `voucher-id` | The voucher identifier | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--since <date>` | Filter events after date (ISO-8601) | none |
| `--until <date>` | Filter events before date (ISO-8601) | none |
| `--limit <n>` | Maximum events to fetch | `100` |

**Examples:**

```bash
# Full history
cashu-ledger history v-1766748473969

# Events from December 2025
cashu-ledger history --since 2025-12-01 --until 2025-12-31 v-1766748473969

# Last 10 events
cashu-ledger history --limit 10 v-1766748473969
```

---

### search

Search vouchers by various criteria across configured relays.

```bash
cashu-ledger search [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--issuer <id>` | Filter by issuer ID | |
| `--status <status>` | Filter by status | |
| `--since <date>` | Issued after date (ISO-8601) | |
| `--until <date>` | Issued before date (ISO-8601) | |
| `--min-value <amount>` | Minimum face value (minor units) | |
| `--max-value <amount>` | Maximum face value (minor units) | |
| `--unit <currency>` | Filter by currency unit | |
| `--has-parents` | Only vouchers with parent contributions | |
| `--is-root` | Only root vouchers (no parents) | |
| `--limit <n>` | Maximum results | `50` |

**Examples:**

```bash
# All vouchers from a merchant
cashu-ledger search --issuer merchant-001

# Issued vouchers from December
cashu-ledger search --status issued --since 2025-12-01

# High-value EUR vouchers
cashu-ledger search --min-value 10000 --unit EUR

# Root vouchers only
cashu-ledger search --is-root --limit 100
```

---

### verify

Verify the cryptographic integrity and consistency of a voucher.

```bash
cashu-ledger verify <voucher-id> [OPTIONS]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `voucher-id` | The voucher identifier | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--check-signature` | Verify issuer Ed25519 signature | `true` |
| `--check-hierarchy` | Verify value conservation across splits | `false` |
| `--check-expiry` | Flag expired vouchers | `true` |

**Verification Checks:**

1. **Signature Verification** - Ed25519 signature from issuer
2. **Value Conservation** - Parent contributions sum equals child values
3. **Issuance Ratio Consistency** - Ratio preserved across splits
4. **Status Consistency** - No invalid status transitions
5. **Expiry Check** - Flag vouchers past expiration

**Examples:**

```bash
# Basic verification
cashu-ledger verify v-1766748473969

# Full verification including hierarchy
cashu-ledger verify --check-hierarchy v-1766748473969
```

---

### diff

Compare two vouchers to understand their relationship or differences.

```bash
cashu-ledger diff <voucher-id-1> <voucher-id-2>
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `voucher-id-1` | First voucher identifier | Yes |
| `voucher-id-2` | Second voucher identifier | Yes |

**Examples:**

```bash
cashu-ledger diff v-1766748473969 v-1766748474000
```

---

### export

Export voucher data for external analysis.

```bash
cashu-ledger export <voucher-id> [OPTIONS]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `voucher-id` | The voucher identifier | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--format <fmt>` | Export format: `json`, `csv` | `json` |
| `--tree` | Include full hierarchy | `false` |
| `--include-events` | Include raw Nostr events | `false` |

**Examples:**

```bash
# Export as JSON
cashu-ledger export v-1766748473969 > voucher.json

# Export tree as CSV
cashu-ledger export --tree --format csv v-1766748473969 > hierarchy.csv
```

---

### watch

Subscribe to real-time updates for a voucher or issuer.

```bash
cashu-ledger watch <voucher-id>
cashu-ledger watch --issuer <issuer-id>
```

**Options:**

| Option | Description |
|--------|-------------|
| `--issuer <id>` | Watch all vouchers from an issuer |

**Examples:**

```bash
# Watch a specific voucher
cashu-ledger watch v-1766748473969

# Watch all vouchers from a merchant
cashu-ledger watch --issuer merchant-001
```

Press `Ctrl+C` to stop watching.

---

## Exit Codes

| Code | Description |
|------|-------------|
| `0` | Success |
| `1` | General error |
| `2` | Voucher not found |
| `3` | Relay connection failed |
| `4` | Verification failed |

## Related Documentation

- [Getting Started](../tutorials/getting-started.md)
- [Voucher Specification](voucher-specification.md)
- [REST API Reference](rest-api.md)
