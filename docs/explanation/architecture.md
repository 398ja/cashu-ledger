# Architecture Overview

This document explains the design and component interactions in cashu-ledger.

## System Purpose

cashu-ledger is a tool for inspecting and analyzing Cashu vouchers stored on Nostr relays. It provides:

- **Voucher inspection** - View detailed voucher information
- **Tree traversal** - Navigate parent-child relationships
- **History tracking** - View status change history
- **Search capabilities** - Find vouchers by various criteria
- **Verification** - Validate signatures and value conservation

## Module Structure

```
cashu-ledger/
├── cashu-ledger-core/           # Domain models, services, relay connectivity
├── cashu-ledger-cli/            # Picocli command-line interface
├── cashu-ledger-web/            # Spring Boot REST API & Web UI
├── cashu-ledger-e2e-tests/      # End-to-end tests
└── cashu-ledger-integration-tests/  # Integration tests
```

### Core Module

The core module contains the domain logic, independent of delivery mechanism (CLI or Web).

**Key Packages:**

| Package | Purpose |
|---------|---------|
| `core.model` | Domain models (VoucherNode, VoucherTree, etc.) |
| `core.service` | Business logic (VoucherLedgerService) |
| `core.relay` | Nostr relay connectivity |
| `core.storage` | Persistent caching layer |
| `core.mapper` | Event-to-domain mapping |
| `core.state` | Voucher state management |

### CLI Module

A Picocli-based command-line interface that wraps the core services.

### Web Module

A Spring Boot application exposing REST endpoints and a web UI.

## Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        Delivery Layer                           │
├─────────────────────────────┬───────────────────────────────────┤
│         CLI Module          │           Web Module              │
│    (Picocli Commands)       │     (Spring Boot REST + UI)       │
└─────────────────────────────┴───────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Service Layer                             │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │               VoucherLedgerService                        │   │
│  │  - fetchVoucher()    - buildTree()    - search()         │   │
│  │  - fetchHistory()    - verify()       - watch()          │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                       Relay Layer                                │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │         CachingRelayConnectionManager (Decorator)         │   │
│  │                         │                                 │   │
│  │         ┌───────────────┴───────────────┐                │   │
│  │         ▼                               ▼                │   │
│  │  ┌─────────────────┐         ┌─────────────────────┐     │   │
│  │  │   EventStore    │         │ NostrRelayConnection│     │   │
│  │  │ (NostrDbEvent   │         │      Manager        │     │   │
│  │  │    Store)       │         │   (WebSocket)       │     │   │
│  │  └─────────────────┘         └─────────────────────┘     │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
           ┌─────────────────┐            ┌─────────────────┐
           │   LMDB Cache    │            │  Nostr Relays   │
           │  (nostrdb-jni)  │            │  (WebSocket)    │
           └─────────────────┘            └─────────────────┘
```

## Data Flow

### Query Flow (Cache-First Strategy)

```
1. User Request (CLI command or REST call)
   │
   ▼
2. VoucherLedgerService.fetchVoucher(voucherId)
   │
   ▼
3. CachingRelayConnectionManager
   │
   ├─► Check EventStore (local cache)
   │   │
   │   ├─► Cache HIT: Return immediately (<1ms)
   │   │
   │   └─► Cache MISS: Continue to relay
   │
   ▼
4. NostrRelayConnectionManager
   │
   ▼
5. Query Nostr Relay via WebSocket
   │
   ▼
6. Receive GenericEvent (kind 30078)
   │
   ▼
7. VoucherEventMapper.toVoucher()
   │
   ▼
8. Store in EventStore (write-through cache)
   │
   ▼
9. Return VoucherNode to caller
```

### Tree Building Algorithm

The tree builder uses breadth-first traversal with batch prefetching:

```
function buildTree(voucherId, maxDepth, direction):
    target = fetchVoucher(voucherId)
    nodes = {voucherId: target}

    if direction in [UP, BOTH]:
        queue = [target]
        while queue and depth < maxDepth:
            batch = collectParentIds(queue)
            parents = batchFetch(batch)  // Single relay query
            queue = parents
            nodes.addAll(parents)
            depth++

    if direction in [DOWN, BOTH]:
        queue = [target]
        while queue and depth < maxDepth:
            batch = collectChildIds(queue)
            children = batchFetch(batch)
            queue = children
            nodes.addAll(children)
            depth++

    return constructTree(nodes, target)
```

## Key Interfaces

### RelayConnectionManager

```java
public interface RelayConnectionManager {
    Optional<RelayEvent> fetchVoucher(String voucherId);
    List<RelayEvent> fetchVoucherBatch(Collection<String> voucherIds);
    List<RelayEvent> fetchHistory(String voucherId, Instant since, Instant until, int limit);
    List<RelayEvent> searchVouchers(VoucherSearchCriteria criteria);
    List<RelayEvent> fetchChildren(String parentVoucherId);
}
```

### EventStore

```java
public interface EventStore extends AutoCloseable {
    boolean store(GenericEvent event, String relayUrl);
    Optional<StoredEvent> findByEventId(String eventId);
    Optional<StoredEvent> findLatestByVoucherId(String voucherId);
    List<StoredEvent> findAllByVoucherId(String voucherId, int limit);
    List<StoredEvent> findChildrenByParentId(String parentVoucherId, int limit);
    StoreStatistics getStatistics();
    boolean isAvailable();
}
```

### VoucherLedgerService

```java
public interface VoucherLedgerService {
    Optional<VoucherNode> fetchVoucher(String voucherId);
    VoucherTree buildTree(String voucherId, int maxDepth, TraversalDirection direction);
    VoucherHistory fetchHistory(String voucherId, Instant since, Instant until, int limit);
    List<VoucherNode> search(VoucherSearchCriteria criteria);
    VerificationReport verify(String voucherId, VerificationOptions options);
}
```

## Design Patterns

### Decorator Pattern (Caching)

`CachingRelayConnectionManager` decorates `NostrRelayConnectionManager` to add transparent caching:

```java
public class CachingRelayConnectionManager implements RelayConnectionManager {
    private final RelayConnectionManager delegate;
    private final EventStore eventStore;

    @Override
    public Optional<RelayEvent> fetchVoucher(String voucherId) {
        // Check cache first
        Optional<StoredEvent> cached = eventStore.findLatestByVoucherId(voucherId);
        if (cached.isPresent()) {
            return toRelayEvent(cached.get());
        }

        // Query relay on cache miss
        Optional<RelayEvent> result = delegate.fetchVoucher(voucherId);

        // Store in cache (write-through)
        result.ifPresent(r -> eventStore.store(r.event(), r.relayUrl()));

        return result;
    }
}
```

### Factory Pattern (Configuration)

The web module uses Spring's conditional bean creation:

```java
@Configuration
public class WebConfig {

    @Bean
    @ConditionalOnProperty(name = "ledger.web.storage.enabled", havingValue = "true")
    public EventStore eventStore(WebLedgerProperties properties) {
        return new NostrDbEventStore(properties.getStorage().toConfig());
    }

    @Bean
    public RelayConnectionManager relayConnectionManager(
            NostrRelayConnectionManager nostrManager,
            @Autowired(required = false) EventStore eventStore) {
        if (eventStore != null && eventStore.isAvailable()) {
            return new CachingRelayConnectionManager(nostrManager, eventStore);
        }
        return nostrManager;
    }
}
```

## Error Handling Strategy

### Graceful Degradation

The system is designed to degrade gracefully:

1. **Native library unavailable** → Falls back to relay-only mode
2. **Cache corrupted** → Continues with relay queries
3. **Relay unavailable** → Tries next configured relay
4. **Partial data** → Returns available data with warnings

### Exception Hierarchy

```
RuntimeException
└── LedgerException
    ├── VoucherNotFoundException
    ├── RelayUnavailableException
    ├── VerificationFailedException
    └── StorageException
```

## Threading Model

### LMDB Constraints

nostrdb-jni uses LMDB which requires transactions to be single-threaded:

```java
// Each operation creates its own transaction
public Optional<StoredEvent> findByEventId(String eventId) {
    try (Transaction txn = ndb.beginTransaction()) {
        return ndb.getNoteById(txn, eventId).map(this::toStoredEvent);
    }
}
```

### Service Thread Safety

- `Ndb` instance is thread-safe (shared across threads)
- `Transaction` is per-thread (created for each operation)
- `VoucherLedgerService` is stateless and thread-safe
- In-memory caches use `ConcurrentHashMap`

## Related Documentation

- [nostrdb-jni Integration](nostrdb-integration.md) - Why we chose nostrdb
- [State Machine Design](state-machine.md) - Voucher lifecycle
- [Configuration Reference](../reference/configuration.md)
