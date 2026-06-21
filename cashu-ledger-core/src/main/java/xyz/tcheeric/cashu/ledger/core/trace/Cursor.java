package xyz.tcheeric.cashu.ledger.core.trace;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * An opaque cursor for keyset (seek) pagination, encoding the
 * {@code (transition_at_ms, event_id)} of the last item on a page. Clients pass it
 * back verbatim; offset pagination is avoided so concurrent ingest cannot skip or
 * duplicate rows (design §5.5 / FR-014).
 *
 * @param transitionAtMs the last row's transition timestamp
 * @param eventId        the last row's event id (tie-breaker)
 */
public record Cursor(long transitionAtMs, String eventId) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** Encodes to a base64url token of {@code transitionAtMs:eventId}. */
    public String encode() {
        String raw = transitionAtMs + ":" + eventId;
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes a token produced by {@link #encode()}; empty if blank or malformed. */
    public static Optional<Cursor> decode(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String raw = new String(DECODER.decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            if (sep <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Cursor(Long.parseLong(raw.substring(0, sep)), raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
