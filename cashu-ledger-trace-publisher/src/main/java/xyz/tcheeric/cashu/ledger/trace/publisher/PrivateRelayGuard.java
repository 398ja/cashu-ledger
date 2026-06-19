package xyz.tcheeric.cashu.ledger.trace.publisher;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Enforces that every configured publish target is private (design §7.2, FR-030).
 * Attempting to start a publisher with any non-private relay is a fatal error in
 * all privacy modes — {@code FULL} would leak raw secrets and even
 * {@code HASHED}/{@code MINIMAL} leak {@code y}.
 */
public final class PrivateRelayGuard {

    /** Error code for the fatal startup failure. */
    public static final String CODE = "PRIVATE_RELAY_REQUIRED";

    private PrivateRelayGuard() {
    }

    /**
     * Validates that {@code relays} is non-empty and every relay is private.
     *
     * @throws TraceabilityPublishException if the set is empty or any relay is public
     */
    public static void requireAllPrivate(List<RelayConfig> relays) {
        if (relays == null || relays.isEmpty()) {
            throw new TraceabilityPublishException(CODE,
                    "No publish relays configured. The traceability publisher requires at "
                            + "least one private, authenticated relay. Suggestion: configure "
                            + "trace.publish-relays with a private relay URL.");
        }
        List<String> publicRelays = relays.stream()
                .filter(r -> !r.isPrivate())
                .map(RelayConfig::url)
                .collect(Collectors.toList());
        if (!publicRelays.isEmpty()) {
            throw new TraceabilityPublishException(CODE,
                    "Refusing to start: non-private publish relay(s) configured " + publicRelays
                            + ". Traceability exposes proof identifiers and must publish only to "
                            + "private, authenticated relays. Suggestion: mark these relays private "
                            + "or remove them.");
        }
    }
}
