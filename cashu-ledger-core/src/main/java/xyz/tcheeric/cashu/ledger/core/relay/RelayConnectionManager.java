package xyz.tcheeric.cashu.ledger.core.relay;

import nostr.event.impl.GenericEvent;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Manages relay connectivity and querying for voucher events.
 */
public interface RelayConnectionManager extends AutoCloseable {

    /**
     * Connects to the provided relays with the given timeout.
     *
     * @param relayUrls relay WebSocket URLs
     * @param timeout   connection timeout
     */
    void connect(List<String> relayUrls, Duration timeout);

    /**
     * Fetches a voucher event by ID across configured relays.
     *
     * @param voucherId voucher identifier (d-tag)
     * @return event and relay metadata if found
     */
    Optional<RelayEvent> fetchVoucher(String voucherId);

    /**
     * Fetches child vouchers that reference the given parent voucher ID.
     *
     * @param parentVoucherId parent voucher identifier
     * @param limit           maximum number of child events to fetch
     * @return events discovered across relays
     */
    List<RelayEvent> searchChildren(String parentVoucherId, int limit);

    /**
     * Fetches all voucher events for a given voucher ID (for history).
     *
     * @param voucherId voucher identifier
     * @param limit     maximum events to fetch
     * @return list of events across relays
     */
    List<RelayEvent> fetchVoucherEvents(String voucherId, int limit);

    /**
     * Broad voucher search (client-side filtering is applied by service).
     *
     * @param limit maximum number of events to return
     * @return events discovered across relays
     */
    List<RelayEvent> searchVouchers(int limit);

    /**
     * Disconnects from all relays.
     */
    void disconnect();

    @Override
    default void close() {
        disconnect();
    }

    /**
     * Container for an event fetched from a relay.
     *
     * @param event    nostr event
     * @param relayUrl relay that provided the event
     */
    record RelayEvent(GenericEvent event, String relayUrl) {
    }
}
