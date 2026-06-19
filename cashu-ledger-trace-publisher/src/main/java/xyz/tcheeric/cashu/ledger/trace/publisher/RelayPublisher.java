package xyz.tcheeric.cashu.ledger.trace.publisher;

/**
 * Transport SPI for delivering a signed event to the ledger relay set. Embedders
 * may supply their own implementation (e.g. a nostr-java client) so the publisher
 * core stays free of any specific relay/transport dependency. Delivery is only
 * considered successful when at least one ledger-subscribed relay acknowledges
 * (design FR-16).
 */
public interface RelayPublisher {

    /**
     * Attempts to publish {@code eventJson} (the signed kind-9079 event) to the
     * configured ledger relays.
     *
     * @param eventJson the signed event JSON
     * @param eventId   the event id (for correlating relay OK responses)
     * @return the delivery outcome
     */
    RelayPublishResult publish(String eventJson, String eventId);
}
