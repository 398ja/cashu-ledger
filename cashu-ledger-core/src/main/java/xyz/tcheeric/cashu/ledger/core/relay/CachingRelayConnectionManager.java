package xyz.tcheeric.cashu.ledger.core.relay;

import nostr.event.impl.GenericEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.ledger.core.storage.EventStore;
import xyz.tcheeric.cashu.ledger.core.storage.StoredEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decorator that adds local caching to relay operations.
 *
 * <p>Implements a cache-first strategy: checks the local event store before
 * querying relays. All relay responses are cached for subsequent queries
 * (write-through caching).
 *
 * <p>This decorator is transparent to consumers and maintains the same
 * interface contract as the underlying relay connection manager.
 */
public class CachingRelayConnectionManager implements RelayConnectionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachingRelayConnectionManager.class);

    private final RelayConnectionManager delegate;
    private final EventStore eventStore;
    private final boolean cacheEnabled;

    /**
     * Creates a caching relay connection manager.
     *
     * @param delegate     the underlying relay connection manager
     * @param eventStore   the event store for caching
     * @param cacheEnabled whether caching is enabled
     */
    public CachingRelayConnectionManager(
            RelayConnectionManager delegate,
            EventStore eventStore,
            boolean cacheEnabled) {
        this.delegate = delegate;
        this.eventStore = eventStore;
        this.cacheEnabled = cacheEnabled && eventStore != null && eventStore.isAvailable();

        if (this.cacheEnabled) {
            LOGGER.info("caching_relay_manager_initialized cache_enabled=true");
        } else {
            LOGGER.info("caching_relay_manager_initialized cache_enabled=false");
        }
    }

    /**
     * Creates a caching relay connection manager with caching enabled.
     *
     * @param delegate   the underlying relay connection manager
     * @param eventStore the event store for caching
     */
    public CachingRelayConnectionManager(RelayConnectionManager delegate, EventStore eventStore) {
        this(delegate, eventStore, true);
    }

    @Override
    public void connect(List<String> relayUrls, Duration timeout) {
        delegate.connect(relayUrls, timeout);
    }

    @Override
    public Optional<RelayEvent> fetchVoucher(String voucherId) {
        if (cacheEnabled) {
            Optional<StoredEvent> cached = eventStore.findLatestByVoucherId(voucherId);
            if (cached.isPresent()) {
                LOGGER.debug("cache_hit operation=fetchVoucher voucher_id={}", voucherId);
                return Optional.of(toRelayEvent(cached.get()));
            }
            LOGGER.debug("cache_miss operation=fetchVoucher voucher_id={}", voucherId);
        }

        Optional<RelayEvent> relayResult = delegate.fetchVoucher(voucherId);

        if (cacheEnabled && relayResult.isPresent()) {
            storeEvent(relayResult.get());
        }

        return relayResult;
    }

    @Override
    public List<RelayEvent> searchChildren(String parentVoucherId, int limit) {
        if (cacheEnabled) {
            List<StoredEvent> cached = eventStore.findChildrenByParentId(parentVoucherId, limit);
            if (!cached.isEmpty()) {
                LOGGER.debug("cache_hit operation=searchChildren parent_id={} count={}",
                        parentVoucherId, cached.size());
                return toRelayEvents(cached);
            }
            LOGGER.debug("cache_miss operation=searchChildren parent_id={}", parentVoucherId);
        }

        List<RelayEvent> relayResults = delegate.searchChildren(parentVoucherId, limit);

        if (cacheEnabled) {
            storeEvents(relayResults);
        }

        return relayResults;
    }

    @Override
    public List<RelayEvent> fetchVoucherEvents(String voucherId, int limit) {
        if (cacheEnabled) {
            List<StoredEvent> cached = eventStore.findAllByVoucherId(voucherId, limit);
            if (!cached.isEmpty()) {
                LOGGER.debug("cache_hit operation=fetchVoucherEvents voucher_id={} count={}",
                        voucherId, cached.size());
                return toRelayEvents(cached);
            }
            LOGGER.debug("cache_miss operation=fetchVoucherEvents voucher_id={}", voucherId);
        }

        List<RelayEvent> relayResults = delegate.fetchVoucherEvents(voucherId, limit);

        if (cacheEnabled) {
            storeEvents(relayResults);
        }

        return relayResults;
    }

    @Override
    public List<RelayEvent> searchVouchers(int limit) {
        // For broad searches, we augment relay results with cached data
        // to provide a more complete picture
        List<RelayEvent> relayResults = delegate.searchVouchers(limit);

        if (cacheEnabled) {
            // Store new results
            storeEvents(relayResults);

            // If relay returned fewer than requested, supplement with cached data
            if (relayResults.size() < limit) {
                List<StoredEvent> cached = eventStore.findAllVouchers(limit);
                Set<String> seenIds = new HashSet<>();
                relayResults.forEach(e -> {
                    if (e.event().getId() != null) {
                        seenIds.add(e.event().getId());
                    }
                });

                List<RelayEvent> combined = new ArrayList<>(relayResults);
                for (StoredEvent stored : cached) {
                    if (stored.eventId() != null && !seenIds.contains(stored.eventId())) {
                        combined.add(toRelayEvent(stored));
                        seenIds.add(stored.eventId());
                        if (combined.size() >= limit) {
                            break;
                        }
                    }
                }

                LOGGER.debug("search_augmented relay_count={} cached_added={} total={}",
                        relayResults.size(), combined.size() - relayResults.size(), combined.size());
                return combined;
            }
        }

        return relayResults;
    }

    @Override
    public void disconnect() {
        delegate.disconnect();
    }

    @Override
    public void close() {
        delegate.close();
        // Note: We don't close the eventStore here as it may be shared
    }

    /**
     * Returns whether caching is enabled and operational.
     *
     * @return true if cache is active
     */
    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    /**
     * Returns the underlying event store.
     *
     * @return the event store, or null if caching is disabled
     */
    public EventStore getEventStore() {
        return eventStore;
    }

    /**
     * Returns the delegate relay connection manager.
     *
     * @return the delegate
     */
    public RelayConnectionManager getDelegate() {
        return delegate;
    }

    private RelayEvent toRelayEvent(StoredEvent stored) {
        return new RelayEvent(stored.event(), stored.relayUrl());
    }

    private List<RelayEvent> toRelayEvents(List<StoredEvent> stored) {
        return stored.stream()
                .map(this::toRelayEvent)
                .toList();
    }

    private void storeEvent(RelayEvent relayEvent) {
        try {
            GenericEvent event = relayEvent.event();
            if (event != null) {
                eventStore.store(event, relayEvent.relayUrl());
            }
        } catch (Exception e) {
            LOGGER.warn("cache_store_failed event_id={} error={}",
                    relayEvent.event() != null ? relayEvent.event().getId() : "null",
                    e.getMessage());
        }
    }

    private void storeEvents(List<RelayEvent> relayEvents) {
        for (RelayEvent relayEvent : relayEvents) {
            storeEvent(relayEvent);
        }
    }
}
