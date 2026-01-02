package xyz.tcheeric.cashu.ledger.core.storage;

import nostr.event.impl.GenericEvent;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction for local event storage.
 *
 * <p>Implementations provide persistent caching of Nostr events for fast
 * subsequent queries. The store is thread-safe for concurrent access.
 */
public interface EventStore extends AutoCloseable {

    /**
     * Stores a Nostr event from a relay.
     *
     * @param event    the event to store
     * @param relayUrl source relay URL
     * @return true if event was new, false if duplicate
     */
    boolean store(GenericEvent event, String relayUrl);

    /**
     * Retrieves an event by its Nostr event ID.
     *
     * @param eventId the 64-character hex event ID
     * @return the stored event, or empty if not found
     */
    Optional<StoredEvent> findByEventId(String eventId);

    /**
     * Finds the latest event for a voucher (by d-tag).
     *
     * @param voucherId the voucher identifier (without "voucher:" prefix)
     * @return the latest stored event, or empty if not found
     */
    Optional<StoredEvent> findLatestByVoucherId(String voucherId);

    /**
     * Finds all events for a voucher (for history).
     *
     * @param voucherId the voucher identifier
     * @param limit     maximum events to return
     * @return list of events, sorted by creation time descending
     */
    List<StoredEvent> findAllByVoucherId(String voucherId, int limit);

    /**
     * Finds vouchers referencing a parent voucher ID.
     *
     * @param parentVoucherId the parent voucher identifier
     * @param limit           maximum events to return
     * @return list of child voucher events
     */
    List<StoredEvent> findChildrenByParentId(String parentVoucherId, int limit);

    /**
     * Searches vouchers by author pubkey.
     *
     * @param pubkey the 64-character hex public key
     * @param limit  maximum events to return
     * @return list of voucher events by the author
     */
    List<StoredEvent> findByAuthor(String pubkey, int limit);

    /**
     * Searches all voucher events (kind 30078).
     *
     * @param limit maximum events to return
     * @return list of voucher events
     */
    List<StoredEvent> findAllVouchers(int limit);

    /**
     * Checks if the store contains an event by ID.
     *
     * @param eventId the 64-character hex event ID
     * @return true if the event exists in the store
     */
    boolean contains(String eventId);

    /**
     * Returns whether the store is available for operations.
     *
     * @return true if the store is operational
     */
    boolean isAvailable();

    /**
     * Returns statistics about the store.
     *
     * @return current store statistics
     */
    StoreStatistics getStatistics();

    /**
     * Closes the store and releases resources.
     */
    @Override
    void close();
}
