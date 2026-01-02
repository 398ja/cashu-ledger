package xyz.tcheeric.cashu.ledger.core.storage;

import nostr.event.impl.GenericEvent;

import java.time.Instant;

/**
 * Wrapper for a cached event with storage metadata.
 *
 * @param event    the Nostr event
 * @param relayUrl the relay that provided the event
 * @param cachedAt when the event was cached
 */
public record StoredEvent(
        GenericEvent event,
        String relayUrl,
        Instant cachedAt
) {

    /**
     * Creates a stored event with the current timestamp.
     *
     * @param event    the Nostr event
     * @param relayUrl the relay that provided the event
     * @return a new stored event
     */
    public static StoredEvent of(GenericEvent event, String relayUrl) {
        return new StoredEvent(event, relayUrl, Instant.now());
    }

    /**
     * Returns the event ID.
     *
     * @return the event ID or null if not set
     */
    public String eventId() {
        return event != null ? event.getId() : null;
    }

    /**
     * Returns the event kind.
     *
     * @return the event kind or 0 if not set
     */
    public int kind() {
        return event != null ? event.getKind() : 0;
    }

    /**
     * Returns the event creation timestamp.
     *
     * @return the creation timestamp or null if not set
     */
    public Instant createdAt() {
        return event != null && event.getCreatedAt() != null
                ? Instant.ofEpochSecond(event.getCreatedAt())
                : null;
    }
}
