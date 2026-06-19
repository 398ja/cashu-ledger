package xyz.tcheeric.cashu.ledger.trace.publisher;

/**
 * A configured publish target. Traceability events may only be published to
 * private, authenticated relays (design §7.2); {@code isPrivate} records the
 * operator's assertion that the relay is access-controlled.
 *
 * @param url       relay websocket URL (e.g. {@code wss://relay.imani.casa})
 * @param isPrivate whether the relay is private/authenticated
 * @param role      operator-assigned role label (e.g. {@code primary}, {@code fallback})
 */
public record RelayConfig(String url, boolean isPrivate, String role) {

    public RelayConfig {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("relay url must be present");
        }
    }

    public static RelayConfig privateRelay(String url) {
        return new RelayConfig(url, true, "primary");
    }
}
