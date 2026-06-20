package xyz.tcheeric.cashu.ledger.trace.core;

import java.util.Optional;

/**
 * A transaction event as held by the ledger: the domain {@link TransactionEvent}
 * plus, when available, the raw signed Nostr event JSON it was parsed from (kept
 * for {@code FULL}-mode round-trips and audit). The raw JSON is absent for events
 * reconstructed from an index or a tombstone.
 *
 * @param event      the domain event
 * @param rawEventJson the original signed Nostr event JSON, if retained
 */
public record StoredEvent(TransactionEvent event, Optional<String> rawEventJson) {

    public StoredEvent {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        rawEventJson = rawEventJson == null ? Optional.empty() : rawEventJson;
    }

    /** A stored event without retained raw JSON. */
    public static StoredEvent of(TransactionEvent event) {
        return new StoredEvent(event, Optional.empty());
    }
}
