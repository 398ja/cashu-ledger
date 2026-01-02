# nostrdb-jni Storage Layer Usage Guide

This guide explains how to enable and configure the persistent event caching layer backed by nostrdb-jni.

## Overview

The cashu-ledger project includes an optional high-performance local caching layer using nostrdb-jni (backed by LMDB). When enabled, this layer:

- **Caches voucher events locally** for sub-millisecond subsequent queries
- **Reduces relay round-trips** through cache-first query strategy
- **Persists across restarts** so previously fetched vouchers are immediately available
- **Optimizes tree traversal** with batch prefetching at each level

## Configuration

### Web Module (Spring Boot)

Add the following to `application.yml`:

```yaml
ledger:
  web:
    storage:
      enabled: true                          # Enable caching (default: false)
      path: ${user.home}/.cashu-ledger/ndb   # Database location
      max-size-bytes: 536870912              # 512MB max size
      event-ttl: 30d                         # Event time-to-live
```

**Configuration Properties:**

| Property | Description | Default |
|----------|-------------|---------|
| `enabled` | Enable/disable local caching | `false` |
| `path` | Database directory path | `~/.cashu-ledger/ndb` |
| `max-size-bytes` | Maximum database size | 512MB |
| `event-ttl` | Event retention period | 30 days |

### CLI Module

Use command-line options:

```bash
# Enable caching with default path
cashu-ledger inspect <voucher-id>

# Custom storage path
cashu-ledger --storage-path /path/to/ndb inspect <voucher-id>

# Disable caching (relay-only mode)
cashu-ledger --no-cache inspect <voucher-id>
```

**CLI Options:**

| Option | Description |
|--------|-------------|
| `--storage-path <path>` | Path to local event store database |
| `--no-cache` | Disable local caching (relay-only mode) |

## Performance Expectations

| Operation | Cache Cold | Cache Warm |
|-----------|------------|------------|
| Single voucher fetch | 100-500ms (relay) | <1ms |
| Tree traversal (depth 5) | 2-10s | <10ms |
| Voucher history (100 events) | 500ms-2s | <5ms |
| Search (50 results) | 1-5s | <10ms |

## Architecture

```
VoucherLedgerService
       │
       ▼
CachingRelayConnectionManager (cache-first decorator)
       │
       ├──► EventStore (local cache)
       │        │
       │        └──► NostrDbEventStore (LMDB-backed)
       │
       └──► NostrRelayConnectionManager (relay fallback)
                │
                └──► WebSocket → Nostr Relays
```

## How It Works

### Cache-First Strategy

1. **Query arrives** (e.g., `fetchVoucher("voucher-123")`)
2. **Check local cache** via `EventStore.findLatestByVoucherId()`
3. **If cached:** Return immediately (sub-millisecond)
4. **If not cached:** Query relay, cache result, return

### Write-Through Caching

All events fetched from relays are automatically stored in the local cache:

```java
Optional<RelayEvent> result = delegate.fetchVoucher(voucherId);
result.ifPresent(r -> eventStore.store(r.event(), r.relayUrl()));
return result;
```

### Batch Prefetching

Tree traversal operations use batch fetching to minimize round-trips:

1. Collect all parent/child IDs at current level
2. Check cache for all IDs (single pass)
3. Batch-fetch missing IDs from relay
4. Process fetched nodes, move to next level

## Native Library Requirements

The storage layer requires the nostrdb-jni native library:

| Platform | Requirements |
|----------|--------------|
| Linux | glibc 2.17+, x64/arm64 |
| macOS | 10.13+, x64/arm64 |
| Windows | Visual C++ Runtime, x64 |

### Graceful Degradation

If the native library is unavailable, the system automatically falls back to relay-only mode:

```
[WARN] nostrdb_native_unavailable platform=... error=...
```

No configuration changes are needed; the application continues to function normally using direct relay queries.

## Monitoring

### Store Statistics

The `EventStore.getStatistics()` method provides runtime metrics:

```java
StoreStatistics stats = eventStore.getStatistics();
// stats.voucherEvents()     - Number of cached voucher events
// stats.databaseSizeBytes() - Current database size on disk
// stats.available()         - Whether the store is operational
```

### Log Messages

Key log events to monitor:

| Log Pattern | Description |
|-------------|-------------|
| `event_store_opened` | Database successfully initialized |
| `cache_hit operation=fetchVoucher` | Cache hit, no relay query needed |
| `cache_miss operation=fetchVoucher` | Cache miss, querying relay |
| `batch_cache_lookup` | Batch operation statistics |
| `nostrdb_native_unavailable` | Native library not loaded |

## Troubleshooting

### Cache Not Working

1. Verify `storage.enabled: true` in configuration
2. Check logs for `nostrdb_native_unavailable` warnings
3. Ensure the storage path is writable
4. Verify native library is available for your platform

### Performance Issues

1. Check database size doesn't exceed `max-size-bytes`
2. Monitor for `cache_miss` frequency in logs
3. Consider reducing `event-ttl` for large datasets

### Database Corruption

If the database becomes corrupted:

1. Stop the application
2. Delete the database directory (default: `~/.cashu-ledger/ndb`)
3. Restart the application (database will be recreated)
