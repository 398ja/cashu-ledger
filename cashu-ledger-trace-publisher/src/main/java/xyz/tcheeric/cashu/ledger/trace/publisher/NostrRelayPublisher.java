package xyz.tcheeric.cashu.ledger.trace.publisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import nostr.client.springwebsocket.NostrRelayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link RelayPublisher} over nostr-java's relay client. Publishes the exact signed
 * event JSON verbatim inside an {@code ["EVENT", <event>]} message — never re-serialised — so
 * the bytes the ledger verifies are the bytes the signer produced. Delivery succeeds when at
 * least one configured private relay returns {@code ["OK", <id>, true, …]} within the timeout
 * (design FR-16). All targets must be private (enforced at construction, §7.2 / FR-030).
 */
public final class NostrRelayPublisher implements RelayPublisher, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NostrRelayPublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<RelayConfig> relays;
    private final long timeoutSeconds;
    private final Map<String, NostrRelayClient> clients = new ConcurrentHashMap<>();

    public NostrRelayPublisher(List<RelayConfig> relays, long timeoutSeconds) {
        PrivateRelayGuard.requireAllPrivate(relays);
        this.relays = List.copyOf(relays);
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public RelayPublishResult publish(String eventJson, String eventId) {
        String message = "[\"EVENT\"," + eventJson + "]";
        List<String> failures = new ArrayList<>();
        for (RelayConfig relay : relays) {
            try {
                List<String> responses = clientFor(relay.url())
                        .sendAsync(message).get(timeoutSeconds, TimeUnit.SECONDS);
                if (acknowledged(responses, eventId)) {
                    return RelayPublishResult.delivered();
                }
                failures.add(relay.url() + ": no OK (" + responses + ")");
            } catch (Exception e) {
                failures.add(relay.url() + ": " + e.getMessage());
                LOGGER.warn("trace_publish_relay_failed relay={} event_id={} error={}",
                        relay.url(), eventId, e.getMessage());
            }
        }
        return RelayPublishResult.failed(String.join("; ", failures));
    }

    /** Whether any relay response is an {@code OK} acknowledgement for this event id. */
    static boolean acknowledged(List<String> responses, String eventId) {
        for (String response : responses) {
            try {
                JsonNode node = MAPPER.readTree(response);
                if (node.isArray() && node.size() >= 3
                        && "OK".equals(node.get(0).asText())
                        && eventId.equals(node.get(1).asText())
                        && node.get(2).asBoolean()) {
                    return true;
                }
            } catch (Exception ignored) {
                // not an OK frame; keep scanning
            }
        }
        return false;
    }

    private NostrRelayClient clientFor(String url) {
        return clients.computeIfAbsent(url, u -> {
            try {
                return new NostrRelayClient(u);
            } catch (Exception e) {
                throw new TraceabilityPublishException("RELAY_CONNECT_FAILED",
                        "Failed to connect to publish relay " + u + ": " + e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        clients.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                LOGGER.debug("trace_publish_relay_close_failed error={}", e.getMessage());
            }
        });
        clients.clear();
    }
}
