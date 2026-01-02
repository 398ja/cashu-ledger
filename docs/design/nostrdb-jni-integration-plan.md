# nostrdb-jni Integration Plan for cashu-ledger Performance Optimization

## Executive Summary

This plan outlines the integration of **nostrdb-jni** (a high-performance embedded Nostr event database backed by LMDB) into **cashu-ledger** to address critical performance bottlenecks and enable persistent local caching of voucher events.

### Expected Benefits
- **Sub-millisecond queries** for cached voucher events (vs 100-500ms relay queries)
- **Offline operation** with previously fetched data
- **Fast tree traversal** without redundant relay queries (10ms vs 2-10s)
- **Indexed searches** by voucher ID, author, status, and parent relationships

---

## 1. Current State Analysis

### 1.1 cashu-ledger Architecture

```
cashu-ledger/
├── cashu-ledger-core      # Domain models, services, relay connectivity
├── cashu-ledger-cli       # PicoCLI command-line interface
└── cashu-ledger-web       # Spring Boot REST API & Web UI
```

**Key Components:**
| Component | File | Purpose |
|-----------|------|---------|
| RelayConnectionManager | `core/relay/RelayConnectionManager.java` | Interface for relay operations |
| NostrRelayConnectionManager | `core/relay/NostrRelayConnectionManager.java` | WebSocket relay implementation |
| VoucherLedgerService | `core/service/VoucherLedgerService.java` | Main service interface |
| VoucherLedgerServiceImpl | `core/service/VoucherLedgerServiceImpl.java` | Service with TTL cache |
| VoucherEventMapper | `core/mapper/VoucherEventMapper.java` | Nostr event → VoucherNode |
| VoucherStateJournal | `core/state/VoucherStateJournal.java` | In-memory state management |

### 1.2 Current Data Flow

```
CLI/Web Request
    ↓
VoucherLedgerService.fetchVoucher()
    ↓
[Check in-memory cache (30s TTL)]
    ↓ (cache miss)
NostrRelayConnectionManager → WebSocket → Relay
    ↓
GenericEvent (kind 30078)
    ↓
VoucherEventMapper.toVoucher()
    ↓
VoucherNode → [Cache and return]
```

### 1.3 Identified Performance Bottlenecks

| Bottleneck | Impact | Location |
|------------|--------|----------|
| **No persistent storage** | Every restart requires fresh relay queries | VoucherLedgerServiceImpl |
| **Sequential relay queries** | Total time = sum of individual relay times | NostrRelayConnectionManager:106-125 |
| **Tree traversal** | Exponential relay queries for deep trees | VoucherLedgerServiceImpl:341-391 |
| **No indexing** | Full-scan client-side filtering | NostrRelayConnectionManager:178-203 |
| **JSON parsing overhead** | Jackson parsing per event | NostrRelayConnectionManager:429-479 |
| **No subscription reuse** | New subscription per query | Throughout relay manager |

### 1.4 Current Storage Mechanism

**In-Memory Only:**
```java
// VoucherLedgerServiceImpl.java:47-49
private final Map<String, CacheEntry<VoucherNode>> voucherCache = new ConcurrentHashMap<>();
private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);
```

- 30-second TTL cache
- Lost on application restart
- VoucherStateJournal also in-memory only

---

## 2. nostrdb-jni Capabilities

### 2.1 Overview

**nostrdb-jni** is a Java binding for [nostrdb](https://github.com/damus-io/nostrdb), providing:
- Embedded key-value store backed by LMDB
- Sub-millisecond queries via memory-mapped I/O
- Zero-copy reads from page cache
- Thread-safe database instance

**Location:** `~/IdeaProjects/nostrdb-jni`
**Maven Artifact:** `xyz.tcheeric:nostrdb-jni:0.1.0-SNAPSHOT`

### 2.2 Key APIs

| Class | Thread-Safe | Purpose |
|-------|-------------|---------|
| `Ndb` | Yes | Main database (shared instance) |
| `Transaction` | No | Read transaction (per-thread) |
| `Filter` | No | Query builder |
| `Note` | Yes | Event data (immutable) |
| `Subscription` | No | Real-time event notifications |

### 2.3 Query Capabilities

```java
// Example: Query voucher events by d-tag
try (Filter filter = Filter.builder()
        .kinds(30078)                           // Voucher event kind
        .dTag("voucher:" + voucherId)           // D-tag filter
        .limit(100)
        .build()) {
    List<Note> notes = ndb.queryNotes(txn, filter, 100);
}
```

**Supported Filters:**
- `kinds(int...)` - Event kind filtering
- `authors(String...)` - Author pubkey filtering
- `dTag(String...)` - Parameterized event identifier (voucher ID)
- `eTag(String...)` - Event references
- `since(long)` / `until(long)` - Time range
- `search(String)` - Full-text search

### 2.4 Threading Model (Critical)

**LMDB Constraint:** One transaction per thread
```java
// CORRECT: Create transaction per operation
try (Transaction txn = ndb.beginTransaction()) {
    ndb.getNoteById(txn, eventId);
}

// INCORRECT: Sharing transaction across threads
```

---

## 3. Architecture Design

### 3.1 Design Pattern: Caching Decorator

```
VoucherLedgerServiceImpl
       │
       ▼
CachingRelayConnectionManager (NEW - wraps and delegates)
       │
       ▼
NostrRelayConnectionManager (existing - unchanged)
       │
       ▼
NostrDbEventStore (NEW - nostrdb-jni wrapper)
```

**Benefits:**
- Backward compatibility (relay-only mode preserved)
- Single responsibility (caching isolated)
- Testability (layers tested independently)
- Optional activation via configuration

### 3.2 New Package Structure

```
cashu-ledger-core/src/main/java/xyz/tcheeric/cashu/ledger/core/
├── storage/                              # NEW PACKAGE
│   ├── EventStore.java                   # Interface
│   ├── NostrDbEventStore.java            # nostrdb-jni implementation
│   ├── EventStoreConfig.java             # Configuration record
│   └── StoredEvent.java                  # Cached event wrapper
├── relay/
│   ├── CachingRelayConnectionManager.java # NEW - Decorator
│   ├── RelayConnectionManager.java        # Existing (unchanged)
│   └── NostrRelayConnectionManager.java   # Existing (unchanged)
└── ...
```

### 3.3 Interface Definitions

#### EventStore Interface

```java
public interface EventStore extends AutoCloseable {
    boolean store(GenericEvent event, String relayUrl);
    Optional<StoredEvent> findByEventId(String eventId);
    Optional<StoredEvent> findLatestByVoucherId(String voucherId);
    List<StoredEvent> findAllByVoucherId(String voucherId, int limit);
    List<StoredEvent> findChildrenByParentId(String parentVoucherId, int limit);
    List<StoredEvent> findByAuthor(String pubkey, int limit);
    List<StoredEvent> findAllVouchers(int limit);
    boolean contains(String eventId);
    StoreStatistics getStatistics();
}
```

#### StoredEvent Record

```java
public record StoredEvent(
    GenericEvent event,
    String relayUrl,
    Instant cachedAt
) {}
```

#### EventStoreConfig Record

```java
public record EventStoreConfig(
    Path databasePath,
    long maxSizeBytes,
    Duration eventTtl,
    boolean readOnly
) {
    public static EventStoreConfig defaults() {
        return new EventStoreConfig(
            Path.of(System.getProperty("user.home"), ".cashu-ledger", "ndb"),
            512L * 1024 * 1024, // 512MB
            Duration.ofDays(30),
            false
        );
    }
}
```

### 3.4 CachingRelayConnectionManager Pattern

```java
public class CachingRelayConnectionManager implements RelayConnectionManager {

    private final RelayConnectionManager delegate;
    private final EventStore eventStore;

    @Override
    public Optional<RelayEvent> fetchVoucher(String voucherId) {
        // 1. Check cache first (sub-ms)
        Optional<StoredEvent> cached = eventStore.findLatestByVoucherId(voucherId);
        if (cached.isPresent()) {
            return toRelayEvent(cached.get());
        }

        // 2. Query relay on cache miss
        Optional<RelayEvent> relayResult = delegate.fetchVoucher(voucherId);

        // 3. Store in cache (write-through)
        relayResult.ifPresent(r -> eventStore.store(r.event(), r.relayUrl()));

        return relayResult;
    }
}
```

---

## 4. Integration Points

### 4.1 Files to Modify

| File | Changes |
|------|---------|
| `pom.xml` (parent) | Add nostrdb-jni version property and dependency management |
| `cashu-ledger-core/pom.xml` | Add nostrdb-jni dependency (optional) |
| `cashu-ledger-web/config/WebConfig.java` | Wire CachingRelayConnectionManager with conditional bean |
| `cashu-ledger-web/config/WebLedgerProperties.java` | Add storage configuration properties |
| `cashu-ledger-cli/CashuLedgerCommand.java` | Add `--storage-path` and `--no-cache` options |

### 4.2 Files to Create

| File | Purpose |
|------|---------|
| `core/storage/EventStore.java` | Storage interface |
| `core/storage/NostrDbEventStore.java` | nostrdb-jni implementation |
| `core/storage/EventStoreConfig.java` | Configuration record |
| `core/storage/StoredEvent.java` | Cached event wrapper |
| `core/storage/StoreStatistics.java` | Statistics record |
| `core/relay/CachingRelayConnectionManager.java` | Caching decorator |

### 4.3 Web Module Configuration

```yaml
# application.yml
ledger:
  web:
    relays:
      - wss://relay.imani.casa
    timeout: 30s
    cache-ttl: 30s
  storage:
    enabled: true
    path: ${user.home}/.cashu-ledger/ndb
    max-size-bytes: 536870912  # 512MB
    event-ttl: 30d
```

### 4.4 CLI Options

```java
@Option(names = {"--storage-path"},
        description = "Path to local event store",
        defaultValue = "${user.home}/.cashu-ledger/ndb")
private String storagePath;

@Option(names = {"--no-cache"},
        description = "Disable local caching (relay-only mode)")
private boolean noCache;
```

---

## 5. Implementation Phases

### Phase 1: Foundation (Core Storage Layer)
**Effort:** 2-3 tasks

1. Add nostrdb-jni dependency to parent POM
2. Create `storage/` package with interfaces
3. Implement `NostrDbEventStore`
4. Add unit tests

**Files:**
- `pom.xml` (parent)
- `cashu-ledger-core/pom.xml`
- `core/storage/EventStore.java`
- `core/storage/NostrDbEventStore.java`
- `core/storage/EventStoreConfig.java`
- `core/storage/StoredEvent.java`
- `core/storage/NostrDbEventStoreTest.java`

### Phase 2: Caching Decorator
**Effort:** 1-2 tasks

1. Implement `CachingRelayConnectionManager`
2. Add unit tests with mocks

**Files:**
- `core/relay/CachingRelayConnectionManager.java`
- `core/relay/CachingRelayConnectionManagerTest.java`

### Phase 3: Configuration Integration
**Effort:** 2 tasks

1. Add storage properties to Web module
2. Add CLI command-line options
3. Wire conditional bean creation

**Files:**
- `web/config/WebLedgerProperties.java`
- `web/config/WebConfig.java`
- `cli/CashuLedgerCommand.java`
- `web/src/main/resources/application.yml`

### Phase 4: Tree Traversal Optimization
**Effort:** 1 task

1. Update `traverseUp`/`traverseDown` to leverage cache
2. Add batch prefetching for known IDs

**Files:**
- `core/service/VoucherLedgerServiceImpl.java`

### Phase 5: Production Hardening
**Effort:** 2 tasks

1. Add graceful degradation for native library failures
2. Implement store statistics and monitoring
3. Add integration tests
4. Update documentation

**Files:**
- `core/storage/NostrDbEventStore.java`
- `integration-tests/StorageIntegrationTest.java`

---

## 6. Performance Expectations

| Operation | Current (Relay) | Cache Cold | Cache Warm |
|-----------|-----------------|------------|------------|
| Single voucher fetch | 100-500ms | 100-500ms | **<1ms** |
| Tree traversal (depth 5) | 2-10s | 2-10s | **<10ms** |
| History (100 events) | 500ms-2s | 500ms-2s | **<5ms** |
| Search (50 results) | 1-5s | 1-5s | **<10ms** |

---

## 7. Risk Mitigation

### 7.1 LMDB Thread Constraints

**Risk:** Transactions cannot cross threads
**Mitigation:** Create transactions per-operation, never share

```java
public Optional<StoredEvent> findByEventId(String eventId) {
    try (Transaction txn = ndb.beginTransaction()) {
        return ndb.getNoteById(txn, eventId).map(this::toStoredEvent);
    }
}
```

### 7.2 Native Library Availability

**Risk:** nostrdb-jni native library may not load on all platforms
**Mitigation:** Graceful degradation to relay-only mode

```java
public NostrDbEventStore(EventStoreConfig config) {
    try {
        this.ndb = Ndb.open(config.databasePath().toString());
        this.available = true;
    } catch (UnsatisfiedLinkError e) {
        log.warn("nostrdb_native_unavailable, falling back to relay-only mode");
        this.available = false;
    }
}
```

### 7.3 Event Format Compatibility

**Risk:** nostrdb-jni expects specific JSON format
**Mitigation:** Use event JSON serialization through existing nostr-java

```java
// Convert GenericEvent to JSON for nostrdb ingestion
String eventJson = objectMapper.writeValueAsString(event);
ndb.processEvent(eventJson);
```

### 7.4 Database Size Growth

**Risk:** Unbounded database growth
**Mitigation:** Configurable max size and TTL-based eviction

---

## 8. Testing Strategy

### 8.1 Unit Tests

| Test Class | Coverage |
|------------|----------|
| `NostrDbEventStoreTest` | Store/retrieve, query by voucher ID, children lookup, idempotency |
| `CachingRelayConnectionManagerTest` | Cache hit/miss, write-through, disabled mode |

### 8.2 Integration Tests

| Test Class | Coverage |
|------------|----------|
| `StorageIntegrationTest` | Full round-trip, tree traversal performance, concurrent access |

### 8.3 Test Patterns

```java
@Test
void shouldReturnCachedEventOnSubsequentFetch() {
    // Given: Event stored in cache
    GenericEvent event = createVoucherEvent("voucher-123", VoucherStatus.ISSUED);
    eventStore.store(event, "wss://relay.example");

    // When: Fetching same voucher
    Optional<StoredEvent> result = eventStore.findLatestByVoucherId("voucher-123");

    // Then: Returns cached event
    assertThat(result).isPresent();
    assertThat(result.get().event().getId()).isEqualTo(event.getId());
}
```

---

## 9. Backward Compatibility

| Scenario | Behavior |
|----------|----------|
| `storage.enabled=false` (default) | Relay-only mode, existing behavior preserved |
| `storage.enabled=true` | Cache-first with write-through |
| Native library fails to load | Automatic fallback to relay-only |
| CLI with `--no-cache` | Explicit relay-only mode |

---

## 10. Dependencies

### 10.1 New Dependencies

```xml
<!-- Parent POM -->
<properties>
    <nostrdb-jni.version>0.1.0-SNAPSHOT</nostrdb-jni.version>
</properties>

<dependencyManagement>
    <dependency>
        <groupId>xyz.tcheeric</groupId>
        <artifactId>nostrdb-jni</artifactId>
        <version>${nostrdb-jni.version}</version>
    </dependency>
</dependencyManagement>

<!-- Core module -->
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>nostrdb-jni</artifactId>
    <optional>true</optional>
</dependency>
```

### 10.2 Native Library Requirements

- **Linux:** glibc 2.17+, x64/arm64
- **macOS:** 10.13+, x64/arm64
- **Windows:** Visual C++ Runtime, x64

---

## 11. Critical Files Reference

| Purpose | Path |
|---------|------|
| Parent POM | `pom.xml` |
| Core POM | `cashu-ledger-core/pom.xml` |
| Relay Interface | `cashu-ledger-core/src/main/java/xyz/tcheeric/cashu/ledger/core/relay/RelayConnectionManager.java` |
| Relay Implementation | `cashu-ledger-core/src/main/java/xyz/tcheeric/cashu/ledger/core/relay/NostrRelayConnectionManager.java` |
| Service Implementation | `cashu-ledger-core/src/main/java/xyz/tcheeric/cashu/ledger/core/service/VoucherLedgerServiceImpl.java` |
| Web Config | `cashu-ledger-web/src/main/java/xyz/tcheeric/cashu/ledger/web/config/WebConfig.java` |
| CLI Command | `cashu-ledger-cli/src/main/java/xyz/tcheeric/cashu/ledger/cli/CashuLedgerCommand.java` |

---

## 12. Success Criteria

- [x] nostrdb-jni dependency integrated into cashu-ledger-core (commit: be941a7)
- [x] EventStore interface and NostrDbEventStore implementation complete (commit: be941a7)
- [x] CachingRelayConnectionManager decorator working (commit: c9be558)
- [x] Configuration properties for Web and CLI modules (commit: 080613b)
- [x] Unit tests for all new classes (commits: be941a7, c9be558, 9b59b6e)
- [x] Graceful fallback when native library unavailable (commit: be941a7)
- [x] Batch fetch and BFS tree traversal optimization (commit: 9b59b6e)
- [ ] Cache warm queries < 1ms (measured)
- [ ] Tree traversal with warm cache < 10ms for depth 5
- [ ] Integration test for full round-trip caching
- [ ] Documentation updated
