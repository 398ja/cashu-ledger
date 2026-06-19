package xyz.tcheeric.cashu.ledger.trace.core;

import java.time.Instant;
import java.util.Optional;

/**
 * Metadata about the underlying signed Nostr event (kind 9079) that carried a
 * transaction event. Distinct from the domain payload: this describes where and
 * how the event was observed, not what operation it records.
 *
 * @param eventId   Nostr event id (64-char hex), or empty before signing
 * @param kind      Nostr kind (9079 for transaction events)
 * @param relayUrl  relay the event was received from, if known
 * @param signature Schnorr signature hex, if known
 * @param createdAt NIP-01 {@code created_at} (second precision)
 */
public record NostrEventMetadata(
        Optional<String> eventId,
        int kind,
        Optional<String> relayUrl,
        Optional<String> signature,
        Instant createdAt
) {

    /** The Nostr kind reserved for transaction traceability events. */
    public static final int TRACE_EVENT_KIND = 9079;

    public NostrEventMetadata {
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must be present");
        }
        eventId = eventId == null ? Optional.empty() : eventId;
        relayUrl = relayUrl == null ? Optional.empty() : relayUrl;
        signature = signature == null ? Optional.empty() : signature;
    }
}
