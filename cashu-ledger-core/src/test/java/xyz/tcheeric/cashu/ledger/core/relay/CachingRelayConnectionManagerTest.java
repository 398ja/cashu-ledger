package xyz.tcheeric.cashu.ledger.core.relay;

import nostr.base.PublicKey;
import nostr.base.Signature;
import nostr.event.impl.GenericEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.ledger.core.relay.RelayConnectionManager.RelayEvent;
import xyz.tcheeric.cashu.ledger.core.storage.EventStore;
import xyz.tcheeric.cashu.ledger.core.storage.StoredEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CachingRelayConnectionManager.
 *
 * <p>Tests verify the cache-first behavior and write-through caching
 * using mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class CachingRelayConnectionManagerTest {

    private static final String TEST_RELAY = "wss://relay.test";
    private static final String VOUCHER_ID = "voucher-001";
    private static final String EVENT_ID = "a".repeat(64);

    @Mock
    private RelayConnectionManager delegate;

    @Mock
    private EventStore eventStore;

    private CachingRelayConnectionManager cachingManager;

    @BeforeEach
    void setUp() {
        when(eventStore.isAvailable()).thenReturn(true);
        cachingManager = new CachingRelayConnectionManager(delegate, eventStore, true);
    }

    /**
     * Tests that fetchVoucher returns cached event on cache hit.
     */
    @Test
    void shouldReturnCachedEventOnCacheHit() {
        // Given: Event is in cache
        GenericEvent cachedEvent = createVoucherEvent(VOUCHER_ID);
        StoredEvent storedEvent = new StoredEvent(cachedEvent, TEST_RELAY, Instant.now());
        when(eventStore.findLatestByVoucherId(VOUCHER_ID)).thenReturn(Optional.of(storedEvent));

        // When: Fetching voucher
        Optional<RelayEvent> result = cachingManager.fetchVoucher(VOUCHER_ID);

        // Then: Returns cached event without querying relay
        assertThat(result).isPresent();
        assertThat(result.get().event()).isEqualTo(cachedEvent);
        assertThat(result.get().relayUrl()).isEqualTo(TEST_RELAY);
        verify(delegate, never()).fetchVoucher(anyString());
    }

    /**
     * Tests that fetchVoucher queries relay and caches result on cache miss.
     */
    @Test
    void shouldQueryRelayAndCacheOnCacheMiss() {
        // Given: Cache is empty, relay has the event
        when(eventStore.findLatestByVoucherId(VOUCHER_ID)).thenReturn(Optional.empty());
        GenericEvent relayEvent = createVoucherEvent(VOUCHER_ID);
        when(delegate.fetchVoucher(VOUCHER_ID))
                .thenReturn(Optional.of(new RelayEvent(relayEvent, TEST_RELAY)));

        // When: Fetching voucher
        Optional<RelayEvent> result = cachingManager.fetchVoucher(VOUCHER_ID);

        // Then: Returns relay result and caches it
        assertThat(result).isPresent();
        assertThat(result.get().event()).isEqualTo(relayEvent);
        verify(delegate).fetchVoucher(VOUCHER_ID);
        verify(eventStore).store(relayEvent, TEST_RELAY);
    }

    /**
     * Tests that searchChildren returns cached results on cache hit.
     */
    @Test
    void shouldReturnCachedChildrenOnCacheHit() {
        // Given: Children are in cache
        String parentId = "parent-001";
        GenericEvent child1 = createVoucherEvent("child-001");
        GenericEvent child2 = createVoucherEvent("child-002");
        List<StoredEvent> cachedChildren = List.of(
                new StoredEvent(child1, TEST_RELAY, Instant.now()),
                new StoredEvent(child2, TEST_RELAY, Instant.now())
        );
        when(eventStore.findChildrenByParentId(parentId, 10)).thenReturn(cachedChildren);

        // When: Searching children
        List<RelayEvent> result = cachingManager.searchChildren(parentId, 10);

        // Then: Returns cached events without querying relay
        assertThat(result).hasSize(2);
        verify(delegate, never()).searchChildren(anyString(), anyInt());
    }

    /**
     * Tests that searchChildren queries relay and caches results on cache miss.
     */
    @Test
    void shouldQueryRelayForChildrenOnCacheMiss() {
        // Given: Cache is empty
        String parentId = "parent-001";
        when(eventStore.findChildrenByParentId(parentId, 10)).thenReturn(List.of());
        GenericEvent child = createVoucherEvent("child-001");
        when(delegate.searchChildren(parentId, 10))
                .thenReturn(List.of(new RelayEvent(child, TEST_RELAY)));

        // When: Searching children
        List<RelayEvent> result = cachingManager.searchChildren(parentId, 10);

        // Then: Returns relay result and caches it
        assertThat(result).hasSize(1);
        verify(delegate).searchChildren(parentId, 10);
        verify(eventStore).store(child, TEST_RELAY);
    }

    /**
     * Tests that fetchVoucherEvents returns cached history on cache hit.
     */
    @Test
    void shouldReturnCachedHistoryOnCacheHit() {
        // Given: History is in cache
        GenericEvent event1 = createVoucherEvent(VOUCHER_ID);
        GenericEvent event2 = createVoucherEvent(VOUCHER_ID);
        List<StoredEvent> cachedHistory = List.of(
                new StoredEvent(event1, TEST_RELAY, Instant.now()),
                new StoredEvent(event2, TEST_RELAY, Instant.now())
        );
        when(eventStore.findAllByVoucherId(VOUCHER_ID, 100)).thenReturn(cachedHistory);

        // When: Fetching history
        List<RelayEvent> result = cachingManager.fetchVoucherEvents(VOUCHER_ID, 100);

        // Then: Returns cached events without querying relay
        assertThat(result).hasSize(2);
        verify(delegate, never()).fetchVoucherEvents(anyString(), anyInt());
    }

    /**
     * Tests that searchVouchers augments relay results with cached data.
     */
    @Test
    void shouldAugmentSearchWithCachedData() {
        // Given: Relay returns 2 results, cache has 3 more
        GenericEvent relayEvent1 = createVoucherEventWithId("1".repeat(64));
        GenericEvent relayEvent2 = createVoucherEventWithId("2".repeat(64));
        when(delegate.searchVouchers(10))
                .thenReturn(List.of(
                        new RelayEvent(relayEvent1, TEST_RELAY),
                        new RelayEvent(relayEvent2, TEST_RELAY)
                ));

        GenericEvent cachedEvent1 = createVoucherEventWithId("3".repeat(64));
        GenericEvent cachedEvent2 = createVoucherEventWithId("4".repeat(64));
        GenericEvent cachedEvent3 = createVoucherEventWithId("1".repeat(64)); // Duplicate
        when(eventStore.findAllVouchers(10))
                .thenReturn(List.of(
                        new StoredEvent(cachedEvent1, TEST_RELAY, Instant.now()),
                        new StoredEvent(cachedEvent2, TEST_RELAY, Instant.now()),
                        new StoredEvent(cachedEvent3, TEST_RELAY, Instant.now()) // Should be deduped
                ));

        // When: Searching vouchers
        List<RelayEvent> result = cachingManager.searchVouchers(10);

        // Then: Returns combined results (4 unique events)
        assertThat(result).hasSize(4);
    }

    /**
     * Tests that caching is disabled when eventStore is null.
     */
    @Test
    void shouldBypassCacheWhenEventStoreIsNull() {
        // Given: Caching manager with null event store
        CachingRelayConnectionManager managerWithoutCache =
                new CachingRelayConnectionManager(delegate, null, true);

        GenericEvent relayEvent = createVoucherEvent(VOUCHER_ID);
        when(delegate.fetchVoucher(VOUCHER_ID))
                .thenReturn(Optional.of(new RelayEvent(relayEvent, TEST_RELAY)));

        // When: Fetching voucher
        Optional<RelayEvent> result = managerWithoutCache.fetchVoucher(VOUCHER_ID);

        // Then: Returns relay result without cache interaction
        assertThat(result).isPresent();
        assertThat(managerWithoutCache.isCacheEnabled()).isFalse();
    }

    /**
     * Tests that caching is disabled when explicitly set to false.
     */
    @Test
    void shouldBypassCacheWhenDisabled() {
        // Given: Caching manager with cache disabled
        CachingRelayConnectionManager disabledCache =
                new CachingRelayConnectionManager(delegate, eventStore, false);

        GenericEvent relayEvent = createVoucherEvent(VOUCHER_ID);
        when(delegate.fetchVoucher(VOUCHER_ID))
                .thenReturn(Optional.of(new RelayEvent(relayEvent, TEST_RELAY)));

        // When: Fetching voucher
        Optional<RelayEvent> result = disabledCache.fetchVoucher(VOUCHER_ID);

        // Then: Queries relay directly, no cache interaction
        assertThat(result).isPresent();
        verify(eventStore, never()).findLatestByVoucherId(anyString());
        verify(eventStore, never()).store(any(), anyString());
    }

    /**
     * Tests that caching is disabled when event store is unavailable.
     */
    @Test
    void shouldBypassCacheWhenEventStoreUnavailable() {
        // Given: Event store reports unavailable
        when(eventStore.isAvailable()).thenReturn(false);
        CachingRelayConnectionManager unavailableCache =
                new CachingRelayConnectionManager(delegate, eventStore, true);

        GenericEvent relayEvent = createVoucherEvent(VOUCHER_ID);
        when(delegate.fetchVoucher(VOUCHER_ID))
                .thenReturn(Optional.of(new RelayEvent(relayEvent, TEST_RELAY)));

        // When: Fetching voucher
        Optional<RelayEvent> result = unavailableCache.fetchVoucher(VOUCHER_ID);

        // Then: Queries relay directly
        assertThat(result).isPresent();
        assertThat(unavailableCache.isCacheEnabled()).isFalse();
    }

    /**
     * Tests that connect delegates to underlying manager.
     */
    @Test
    void shouldDelegateConnect() {
        // Given: Relay URLs and timeout
        List<String> relayUrls = List.of("wss://relay1.test", "wss://relay2.test");
        Duration timeout = Duration.ofSeconds(30);

        // When: Connecting
        cachingManager.connect(relayUrls, timeout);

        // Then: Delegates to underlying manager
        verify(delegate).connect(relayUrls, timeout);
    }

    /**
     * Tests that disconnect delegates to underlying manager.
     */
    @Test
    void shouldDelegateDisconnect() {
        // When: Disconnecting
        cachingManager.disconnect();

        // Then: Delegates to underlying manager
        verify(delegate).disconnect();
    }

    /**
     * Tests that close delegates to underlying manager.
     */
    @Test
    void shouldDelegateClose() {
        // When: Closing
        cachingManager.close();

        // Then: Delegates to underlying manager
        verify(delegate).close();
    }

    /**
     * Tests that accessor methods return correct values.
     */
    @Test
    void shouldProvideAccessorMethods() {
        // Then: Accessors return correct values
        assertThat(cachingManager.isCacheEnabled()).isTrue();
        assertThat(cachingManager.getEventStore()).isEqualTo(eventStore);
        assertThat(cachingManager.getDelegate()).isEqualTo(delegate);
    }

    /**
     * Tests that cache store failures are handled gracefully.
     */
    @Test
    void shouldHandleCacheStoreFailuresGracefully() {
        // Given: Cache miss and relay returns event, but store fails
        when(eventStore.findLatestByVoucherId(VOUCHER_ID)).thenReturn(Optional.empty());
        GenericEvent relayEvent = createVoucherEvent(VOUCHER_ID);
        when(delegate.fetchVoucher(VOUCHER_ID))
                .thenReturn(Optional.of(new RelayEvent(relayEvent, TEST_RELAY)));
        when(eventStore.store(any(), anyString())).thenThrow(new RuntimeException("Store failed"));

        // When: Fetching voucher
        Optional<RelayEvent> result = cachingManager.fetchVoucher(VOUCHER_ID);

        // Then: Still returns relay result despite cache failure
        assertThat(result).isPresent();
        assertThat(result.get().event()).isEqualTo(relayEvent);
    }

    /**
     * Tests that fetchVouchersBatch returns all cached events on full cache hit.
     */
    @Test
    void shouldReturnAllCachedEventsOnBatchCacheHit() {
        // Given: All vouchers are in cache
        GenericEvent event1 = createVoucherEventWithId("1".repeat(64));
        GenericEvent event2 = createVoucherEventWithId("2".repeat(64));
        when(eventStore.findLatestByVoucherId("voucher-1"))
                .thenReturn(Optional.of(new StoredEvent(event1, TEST_RELAY, Instant.now())));
        when(eventStore.findLatestByVoucherId("voucher-2"))
                .thenReturn(Optional.of(new StoredEvent(event2, TEST_RELAY, Instant.now())));

        // When: Batch fetching vouchers
        List<RelayEvent> results = cachingManager.fetchVouchersBatch(List.of("voucher-1", "voucher-2"));

        // Then: Returns cached events without querying relay
        assertThat(results).hasSize(2);
        verify(delegate, never()).fetchVouchersBatch(any());
    }

    /**
     * Tests that fetchVouchersBatch queries relay for cache misses only.
     */
    @Test
    void shouldQueryRelayForBatchCacheMissesOnly() {
        // Given: One in cache, one not
        GenericEvent cachedEvent = createVoucherEventWithId("1".repeat(64));
        GenericEvent relayEvent = createVoucherEventWithId("2".repeat(64));
        when(eventStore.findLatestByVoucherId("voucher-1"))
                .thenReturn(Optional.of(new StoredEvent(cachedEvent, TEST_RELAY, Instant.now())));
        when(eventStore.findLatestByVoucherId("voucher-2"))
                .thenReturn(Optional.empty());
        when(delegate.fetchVouchersBatch(Set.of("voucher-2")))
                .thenReturn(List.of(new RelayEvent(relayEvent, TEST_RELAY)));

        // When: Batch fetching vouchers
        List<RelayEvent> results = cachingManager.fetchVouchersBatch(List.of("voucher-1", "voucher-2"));

        // Then: Returns both cached and relay events
        assertThat(results).hasSize(2);
        verify(delegate).fetchVouchersBatch(Set.of("voucher-2"));
        verify(eventStore).store(relayEvent, TEST_RELAY);
    }

    /**
     * Tests that fetchVouchersBatch returns empty list for empty input.
     */
    @Test
    void shouldReturnEmptyListForEmptyBatchInput() {
        // When: Batch fetching with empty list
        List<RelayEvent> results = cachingManager.fetchVouchersBatch(List.of());

        // Then: Returns empty list
        assertThat(results).isEmpty();
        verify(delegate, never()).fetchVouchersBatch(any());
    }

    /**
     * Tests that fetchVouchersBatch bypasses cache when disabled.
     */
    @Test
    void shouldBypassCacheForBatchWhenDisabled() {
        // Given: Caching disabled
        CachingRelayConnectionManager disabledCache =
                new CachingRelayConnectionManager(delegate, eventStore, false);
        GenericEvent relayEvent = createVoucherEventWithId("1".repeat(64));
        when(delegate.fetchVouchersBatch(Set.of("voucher-1")))
                .thenReturn(List.of(new RelayEvent(relayEvent, TEST_RELAY)));

        // When: Batch fetching
        List<RelayEvent> results = disabledCache.fetchVouchersBatch(Set.of("voucher-1"));

        // Then: Queries relay directly, no cache interaction
        assertThat(results).hasSize(1);
        verify(eventStore, never()).findLatestByVoucherId(anyString());
    }

    private GenericEvent createVoucherEvent(String voucherId) {
        return createVoucherEventWithId(EVENT_ID);
    }

    private GenericEvent createVoucherEventWithId(String eventId) {
        GenericEvent event = new GenericEvent();
        event.setId(eventId);
        event.setPubKey(new PublicKey("b".repeat(64)));
        event.setSignature(Signature.fromString("0".repeat(128)));
        event.setKind(30078);
        event.setCreatedAt(Instant.now().getEpochSecond());
        event.setContent("");
        return event;
    }
}
