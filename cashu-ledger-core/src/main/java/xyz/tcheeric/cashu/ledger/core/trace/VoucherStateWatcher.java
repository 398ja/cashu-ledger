package xyz.tcheeric.cashu.ledger.core.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import nostr.client.springwebsocket.NostrRelayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;

/**
 * Subscribes to kind-30078 voucher-state events on the configured relays, reduces each
 * to a {@link VoucherStatusObservation}, and records the latest revision in the sidecar.
 * When a voucher first reaches a terminal status it notifies the {@link VoucherTerminalListener}
 * so dependent caches can invalidate. The last-seen {@code created_at} per relay is
 * persisted as a resumable cursor, and {@link #lagSeconds(Instant)} reports ingest lag
 * (design §5.4.1, modelled on {@link TraceSyncEngine}).
 */
public final class VoucherStateWatcher implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoucherStateWatcher.class);
    static final int VOUCHER_KIND = 30078;
    private static final String D_TAG_PREFIX = "voucher:";
    private static final String CURSOR_PREFIX = "voucher_watcher:";

    private final SqliteSidecarIndex index;
    private final VoucherTerminalListener terminalListener;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<AutoCloseable> subscriptions = new ArrayList<>();
    private final List<NostrRelayClient> clients = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong lastCreatedAtS = new AtomicLong(0);

    public VoucherStateWatcher(SqliteSidecarIndex index, VoucherTerminalListener terminalListener) {
        this.index = index;
        this.terminalListener = terminalListener;
    }

    /** Opens a long-lived kind-30078 subscription to {@code relayUrl}, resuming from its cursor. */
    public void subscribe(String relayUrl) {
        lock.lock();
        try {
            NostrRelayClient client = new NostrRelayClient(relayUrl);
            String subscriptionId = "voucher-" + UUID.randomUUID().toString().substring(0, 8);
            long since = index.loadCursor(CURSOR_PREFIX + relayUrl).orElse(0L);
            String filter = since > 0
                    ? "{\"kinds\":[" + VOUCHER_KIND + "],\"since\":" + since + "}"
                    : "{\"kinds\":[" + VOUCHER_KIND + "]}";
            String req = "[\"REQ\",\"" + subscriptionId + "\"," + filter + "]";

            AutoCloseable subscription = client.subscribe(
                    req,
                    message -> handleMessage(message, relayUrl),
                    error -> LOGGER.warn("voucher_watch_error relay={} subscription={} error={}",
                            relayUrl, subscriptionId, error.getMessage()),
                    () -> LOGGER.debug("voucher_watch_eose relay={} subscription={}", relayUrl, subscriptionId));

            clients.add(client);
            subscriptions.add(subscription);
            LOGGER.info("voucher_watch_subscribed relay={} subscription={} since={}",
                    relayUrl, subscriptionId, since);
        } catch (Exception e) {
            throw new TraceStorageException("Failed to subscribe voucher watcher to " + relayUrl, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Handles one raw {@code ["EVENT", subId, {event}]} message, recording the voucher
     * status and advancing the relay cursor. Package-visible for tests.
     */
    void handleMessage(String message, String relayUrl) {
        try {
            JsonNode root = mapper.readTree(message);
            if (!root.isArray() || root.size() < 3 || !"EVENT".equals(root.get(0).asText())) {
                return;
            }
            JsonNode event = root.get(2);
            long createdAt = event.path("created_at").asLong(0);
            parse(event).ifPresent(this::record);
            advanceCursor(relayUrl, createdAt);
        } catch (RuntimeException e) {
            LOGGER.warn("voucher_watch_message_failed relay={} error={}", relayUrl, e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("voucher_watch_message_unreadable relay={} error={}", relayUrl, e.getMessage());
        }
    }

    /** Reduces a kind-30078 event node to an observation, or empty if it is not one. */
    Optional<VoucherStatusObservation> parse(JsonNode event) {
        if (event.path("kind").asInt(-1) != VOUCHER_KIND) {
            return Optional.empty();
        }
        String voucherId = null, status = null, issuerId = null, issuerPubkey = null;
        long stateVersion = 0;
        Long transitionAt = null;
        for (JsonNode tag : event.path("tags")) {
            if (!tag.isArray() || tag.isEmpty()) {
                continue;
            }
            String code = tag.get(0).asText();
            String value = tag.size() > 1 ? tag.get(1).asText() : null;
            switch (code) {
                case "d" -> voucherId = stripPrefix(value);
                case "status" -> status = value;
                case "state_version" -> stateVersion = parseLong(value);
                case "transition_at" -> transitionAt = value != null ? Long.parseLong(value) : null;
                case "issuer_id" -> issuerId = value;
                case "issuer_pubkey" -> issuerPubkey = value;
                default -> { }
            }
        }
        if (voucherId == null || voucherId.isBlank()) {
            return Optional.empty();
        }
        VoucherStatus parsed = VoucherStatus.fromValue(status);
        Instant at = Instant.ofEpochSecond(
                transitionAt != null ? transitionAt : event.path("created_at").asLong(0));
        return Optional.of(new VoucherStatusObservation(voucherId, parsed.name().toLowerCase(),
                stateVersion, at, parsed.isTerminal(),
                Optional.ofNullable(issuerId), Optional.ofNullable(issuerPubkey)));
    }

    /**
     * Records an observation, notifying the listener when it first turns terminal.
     * Returns {@code true} if this observation caused the terminal transition.
     */
    public boolean record(VoucherStatusObservation observation) {
        boolean newlyTerminal = index.upsertVoucherStatus(observation);
        if (newlyTerminal) {
            LOGGER.info("voucher_terminal voucher_id={} status={} state_version={}",
                    observation.voucherId(), observation.status(), observation.stateVersion());
            terminalListener.onVoucherTerminal(observation);
        }
        return newlyTerminal;
    }

    /** Seconds between {@code now} and the newest voucher event seen, or 0 if none. */
    public long lagSeconds(Instant now) {
        long latest = lastCreatedAtS.get();
        return latest == 0 ? 0 : Math.max(0, now.getEpochSecond() - latest);
    }

    private void advanceCursor(String relayUrl, long createdAt) {
        if (createdAt <= 0) {
            return;
        }
        lastCreatedAtS.accumulateAndGet(createdAt, Math::max);
        index.saveCursor(CURSOR_PREFIX + relayUrl, createdAt);
    }

    private static String stripPrefix(String dTag) {
        if (dTag == null) {
            return null;
        }
        return dTag.startsWith(D_TAG_PREFIX) ? dTag.substring(D_TAG_PREFIX.length()) : dTag;
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            subscriptions.forEach(VoucherStateWatcher::closeQuietly);
            clients.forEach(VoucherStateWatcher::closeQuietly);
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
            LOGGER.debug("voucher_watch_close_failed error={}", e.getMessage());
        }
    }
}
