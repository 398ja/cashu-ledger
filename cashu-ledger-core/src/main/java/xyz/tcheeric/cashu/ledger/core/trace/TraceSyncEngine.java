package xyz.tcheeric.cashu.ledger.core.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import nostr.client.springwebsocket.NostrRelayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;

/**
 * Subscribes to kind-9079 trace events on the configured relays and feeds each
 * received event to {@link TraceIngestService} (design §6.1, modelled on the
 * existing relay connection manager). Subscriptions are long-lived so new events
 * stream in after the initial backlog (EOSE).
 */
public final class TraceSyncEngine implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceSyncEngine.class);
    private static final int TRACE_KIND = NostrEventMetadata.TRACE_EVENT_KIND;

    private final TraceIngestService ingestService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<AutoCloseable> subscriptions = new ArrayList<>();
    private final List<NostrRelayClient> clients = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    public TraceSyncEngine(TraceIngestService ingestService) {
        this.ingestService = ingestService;
    }

    /** Opens a long-lived kind-9079 subscription to {@code relayUrl}. */
    public void subscribe(String relayUrl) {
        lock.lock();
        try {
            NostrRelayClient client = new NostrRelayClient(relayUrl);
            String subscriptionId = "trace-" + UUID.randomUUID().toString().substring(0, 8);
            // Raw REQ filter for kind 9079 — built directly because nostr-java's Kind
            // enum does not recognise this application-specific kind.
            String req = "[\"REQ\",\"" + subscriptionId + "\",{\"kinds\":[" + TRACE_KIND + "]}]";

            AutoCloseable subscription = client.subscribe(
                    req,
                    message -> handleMessage(message, relayUrl),
                    error -> LOGGER.warn("trace_sync_error relay={} subscription={} error={}",
                            relayUrl, subscriptionId, error.getMessage()),
                    () -> LOGGER.debug("trace_sync_eose relay={} subscription={}", relayUrl, subscriptionId));

            clients.add(client);
            subscriptions.add(subscription);
            LOGGER.info("trace_sync_subscribed relay={} subscription={}", relayUrl, subscriptionId);
        } catch (Exception e) {
            throw new TraceStorageException("Failed to subscribe to relay " + relayUrl, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Handles one raw relay message of the form {@code ["EVENT", subId, {event}]},
     * forwarding the inner event JSON to the ingest service. Package-visible for tests.
     */
    void handleMessage(String message, String relayUrl) {
        try {
            JsonNode root = mapper.readTree(message);
            if (!root.isArray() || root.size() < 3 || !"EVENT".equals(root.get(0).asText())) {
                return;
            }
            String rawEventJson = root.get(2).toString();
            ingestService.ingest(rawEventJson, relayUrl);
        } catch (RuntimeException e) {
            LOGGER.warn("trace_sync_message_failed relay={} error={}", relayUrl, e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("trace_sync_message_unreadable relay={} error={}", relayUrl, e.getMessage());
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            for (AutoCloseable subscription : subscriptions) {
                closeQuietly(subscription);
            }
            for (NostrRelayClient client : clients) {
                closeQuietly(client);
            }
            subscriptions.clear();
            clients.clear();
        } finally {
            lock.unlock();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            LOGGER.debug("trace_sync_close_failed error={}", e.getMessage());
        }
    }
}
