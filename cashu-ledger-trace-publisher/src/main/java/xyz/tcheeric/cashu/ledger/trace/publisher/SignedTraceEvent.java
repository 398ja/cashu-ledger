package xyz.tcheeric.cashu.ledger.trace.publisher;

/**
 * The result of signing a transaction event: the deterministic Nostr event id, the
 * Schnorr signature, and the complete signed event JSON ready to publish.
 *
 * @param eventId   the deterministic Nostr event id (64-char hex)
 * @param signature the 64-byte Schnorr signature (128-char hex)
 * @param eventJson the full signed event JSON ({@code id, pubkey, created_at, kind, tags, content, sig})
 */
public record SignedTraceEvent(String eventId, String signature, String eventJson) {

    public SignedTraceEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must be present");
        }
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("signature must be present");
        }
        if (eventJson == null || eventJson.isBlank()) {
            throw new IllegalArgumentException("eventJson must be present");
        }
    }
}
