package xyz.tcheeric.cashu.ledger.core.model;

import java.time.Instant;
import java.util.List;

/**
 * Metadata extracted from the underlying Nostr event.
 *
 * @param eventId   event identifier
 * @param pubkey    publisher public key (hex)
 * @param createdAt event creation timestamp
 * @param relay     relay URL that returned the event
 * @param kind      Nostr event kind
 * @param tags      raw tags (code + values) for debugging and output
 */
public record NostrEventMetadata(
        String eventId,
        String pubkey,
        Instant createdAt,
        String relay,
        int kind,
        List<List<String>> tags,
        String signatureHex
) {
}
