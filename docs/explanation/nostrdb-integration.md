# nostrdb-jni Integration Design

This document explains the design rationale for integrating nostrdb-jni into cashu-ledger for persistent event caching.

## Problem Statement

The original cashu-ledger implementation had several performance bottlenecks:

| Bottleneck | Impact |
|------------|--------|
| No persistent storage | Every restart requires fresh relay queries |
| Sequential relay queries | Total time = sum of individual relay times |
| Tree traversal | Exponential relay queries for deep trees |
| No indexing | Full-scan client-side filtering |

A single voucher fetch took 100-500ms, and tree traversal for a depth-5 tree could take 2-10 seconds.

## Solution: nostrdb-jni

We integrated [nostrdb-jni](https://github.com/tcheeric/nostrdb-jni), a Java binding for the high-performance [nostrdb](https://github.com/damus-io/nostrdb) library.

### Why nostrdb?

1. **LMDB-backed** - Memory-mapped I/O for sub-millisecond reads
2. **Zero-copy reads** - Direct access to page cache, no deserialization
3. **Thread-safe** - Single database instance shared across threads
4. **Compact storage** - Efficient binary format for Nostr events
5. **Built-in indexing** - Native support for Nostr event queries

### Performance Comparison

| Operation | Without nostrdb | With nostrdb (warm) |
|-----------|-----------------|---------------------|
| Single voucher fetch | 100-500ms | <1ms |
| Tree traversal (depth 5) | 2-10s | <10ms |
| History (100 events) | 500ms-2s | <5ms |
| Search (50 results) | 1-5s | <10ms |

## Architecture Decision

### Decorator Pattern

We chose the Decorator pattern to add caching transparently:

```
RelayConnectionManager (interface)
        │
        ├── NostrRelayConnectionManager (relay queries)
        │
        └── CachingRelayConnectionManager (decorator)
                │
                └── delegates to NostrRelayConnectionManager
                    + uses EventStore for caching
```

**Benefits:**

1. **Backward compatibility** - Relay-only mode still works
2. **Single responsibility** - Caching isolated from relay logic
3. **Optional activation** - Enabled via configuration
4. **Testability** - Each layer tested independently

### Alternative Considered: Repository Pattern

We considered a full repository abstraction but rejected it because:

- Nostrdb is not a general-purpose database
- We only need caching, not full data management
- Simpler decorator approach reduces complexity

## Implementation Details

### EventStore Interface

```java
public interface EventStore extends AutoCloseable {
    boolean store(GenericEvent event, String relayUrl);
    Optional<StoredEvent> findByEventId(String eventId);
    Optional<StoredEvent> findLatestByVoucherId(String voucherId);
    List<StoredEvent> findAllByVoucherId(String voucherId, int limit);
    List<StoredEvent> findChildrenByParentId(String parentVoucherId, int limit);
    List<StoredEvent> findByAuthor(String pubkey, int limit);
    StoreStatistics getStatistics();
    boolean isAvailable();
}
```

### NostrDbEventStore Implementation

Key implementation choices:

1. **Per-operation transactions** - Each method creates its own transaction to respect LMDB's single-thread-per-transaction constraint

2. **Graceful degradation** - If the native library fails to load, `isAvailable()` returns false and the system falls back to relay-only mode

3. **JSON event storage** - Events are stored as JSON strings, compatible with nostrdb's ingestion format

```java
public boolean store(GenericEvent event, String relayUrl) {
    if (!available) return false;

    String eventJson = objectMapper.writeValueAsString(event);
    return ndb.processEvent(eventJson);
}
```

### CachingRelayConnectionManager

The caching decorator implements a write-through cache strategy:

```java
@Override
public Optional<RelayEvent> fetchVoucher(String voucherId) {
    // 1. Check cache first
    Optional<StoredEvent> cached = eventStore.findLatestByVoucherId(voucherId);
    if (cached.isPresent()) {
        log.debug("cache_hit operation=fetchVoucher voucher_id={}", voucherId);
        return toRelayEvent(cached.get());
    }

    // 2. Query relay on cache miss
    log.debug("cache_miss operation=fetchVoucher voucher_id={}", voucherId);
    Optional<RelayEvent> result = delegate.fetchVoucher(voucherId);

    // 3. Store in cache (write-through)
    result.ifPresent(r -> eventStore.store(r.event(), r.relayUrl()));

    return result;
}
```

### Batch Prefetching

For tree traversal, we use batch fetching to minimize relay round-trips:

```java
@Override
public List<RelayEvent> fetchVoucherBatch(Collection<String> voucherIds) {
    List<RelayEvent> results = new ArrayList<>();
    List<String> cacheMisses = new ArrayList<>();

    // Check cache for all IDs
    for (String id : voucherIds) {
        Optional<StoredEvent> cached = eventStore.findLatestByVoucherId(id);
        if (cached.isPresent()) {
            results.add(toRelayEvent(cached.get()));
        } else {
            cacheMisses.add(id);
        }
    }

    // Batch fetch missing from relay
    if (!cacheMisses.isEmpty()) {
        List<RelayEvent> relayResults = delegate.fetchVoucherBatch(cacheMisses);
        relayResults.forEach(r -> eventStore.store(r.event(), r.relayUrl()));
        results.addAll(relayResults);
    }

    return results;
}
```

## Configuration

### Optional Dependency

nostrdb-jni is an optional dependency in the core module:

```xml
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>nostrdb-jni</artifactId>
    <optional>true</optional>
</dependency>
```

### Conditional Activation

The caching layer is only activated when:
1. `ledger.web.storage.enabled=true` is set
2. The nostrdb-jni native library loads successfully

```java
@Bean
@ConditionalOnProperty(name = "ledger.web.storage.enabled", havingValue = "true")
public EventStore eventStore(WebLedgerProperties properties) {
    return new NostrDbEventStore(properties.getStorage().toConfig());
}
```

## Native Library Handling

### Graceful Fallback

The system handles native library unavailability gracefully:

```java
public NostrDbEventStore(EventStoreConfig config) {
    try {
        this.ndb = Ndb.open(config.databasePath().toString());
        this.available = true;
        log.info("event_store_opened path={}", config.databasePath());
    } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
        log.warn("nostrdb_native_unavailable platform={} error={}",
                 System.getProperty("os.name"), e.getMessage());
        this.available = false;
    }
}
```

### Platform Support

| Platform | Status |
|----------|--------|
| Linux x64 | Supported |
| Linux arm64 | Supported |
| macOS x64 | Supported |
| macOS arm64 (M1/M2) | Supported |
| Windows x64 | Supported |

## Testing Strategy

### Conditional Test Execution

Tests that require the native library use JUnit 5's `@EnabledIf`:

```java
@Test
@EnabledIf("isNativeLibraryAvailable")
void shouldStorAndRetrieveVoucherEvent() {
    // Test implementation
}

static boolean isNativeLibraryAvailable() {
    try {
        Ndb ndb = Ndb.open(tempDir.toString());
        ndb.close();
        return true;
    } catch (Throwable t) {
        return false;
    }
}
```

### Integration Tests

Integration tests verify the full caching flow:

1. Store event in cache
2. Verify cache hit on subsequent fetch
3. Verify cache miss triggers relay query
4. Verify batch operations

## Trade-offs

### Advantages

1. **Dramatic performance improvement** - Sub-millisecond cached queries
2. **Offline operation** - Previously fetched data available without relay
3. **Reduced relay load** - Fewer queries to public relays
4. **Persistent across restarts** - No cold-start penalty

### Limitations

1. **Native library dependency** - Platform-specific binaries required
2. **Storage space** - Database can grow large with many events
3. **Eventual consistency** - Cached data may be stale
4. **JSON format requirements** - Events must match nostrdb's expected format

### Mitigations

1. **Graceful fallback** - Works without native library
2. **Configurable limits** - `max-size-bytes` and `event-ttl` settings
3. **Write-through cache** - Fresh data on every relay fetch
4. **Standard JSON** - Use Jackson for consistent serialization

## Future Improvements

1. **Cache invalidation** - Subscribe to relay updates for invalidation
2. **Background refresh** - Proactively refresh stale entries
3. **Metrics export** - Prometheus metrics for cache hit rates
4. **Compression** - Reduce storage footprint

## Related Documentation

- [Architecture Overview](architecture.md)
- [Enable Local Caching](../how-to/enable-local-caching.md)
- [Configuration Reference](../reference/configuration.md)
