package xyz.tcheeric.cashu.ledger.core.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import nostr.client.springwebsocket.SpringWebSocketClient;
import nostr.client.springwebsocket.StandardWebSocketClient;
import nostr.client.springwebsocket.WebSocketClientIF;
import nostr.event.BaseTag;
import nostr.event.filter.Filterable;
import nostr.event.filter.Filters;
import nostr.event.filter.IdentifierTagFilter;
import nostr.event.filter.KindFilter;
import nostr.event.impl.GenericEvent;
import nostr.event.message.CloseMessage;
import nostr.event.message.ReqMessage;
import nostr.event.tag.GenericTag;
import nostr.base.ElementAttribute;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Relay connection manager backed by nostr-java WebSocket client.
 *
 * <p>ClientContext instances are cached in the clients map for connection reuse
 * and are explicitly closed via {@link #disconnect()} or {@link #closeAndRemoveClient(String)}.
 * The "resource" warning is suppressed because we manage the lifecycle manually.
 */
@Slf4j
@SuppressWarnings("resource") // ClientContext lifecycle managed via disconnect() and closeAndRemoveClient()
public class NostrRelayConnectionManager implements RelayConnectionManager {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final int VOUCHER_KIND = 30078;
    private static final String D_TAG_PREFIX = "voucher:";

    private final ConcurrentHashMap<String, ClientContext> clients = new ConcurrentHashMap<>();
    private List<String> relayUrls = List.of();
    private Duration queryTimeout = Duration.ofSeconds(30);

    @Override
    public void connect(List<String> relayUrls, Duration timeout) {
        this.relayUrls = List.copyOf(Objects.requireNonNull(relayUrls, "relayUrls"));
        this.queryTimeout = Objects.requireNonNull(timeout, "timeout");
        if (relayUrls.isEmpty()) {
            log.warn("relay_connection skipped connect because no relays provided");
            return;
        }
        relayUrls.forEach(url -> clients.computeIfAbsent(url, this::createClient));
        log.info("relay_connection initialized relays={} timeoutMs={}", relayUrls.size(), timeout.toMillis());
    }

    @Override
    public Optional<RelayEvent> fetchVoucher(String voucherId) {
        Objects.requireNonNull(voucherId, "voucherId");
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("voucherId cannot be blank");
        }

        for (String relayUrl : relayUrls) {
            try {
                ClientContext context = getOrReconnectClient(relayUrl);
                Optional<GenericEvent> event = querySingleRelay(context.client(), relayUrl, voucherId);
                if (event.isPresent()) {
                    return Optional.of(new RelayEvent(event.get(), relayUrl));
                }
            } catch (Exception e) {
                if (isClosedSessionError(e)) {
                    log.info("relay_connection_closed relay={} reconnecting", relayUrl);
                    closeAndRemoveClient(relayUrl);
                    try {
                        ClientContext newContext = getOrReconnectClient(relayUrl);
                        Optional<GenericEvent> event = querySingleRelay(newContext.client(), relayUrl, voucherId);
                        if (event.isPresent()) {
                            return Optional.of(new RelayEvent(event.get(), relayUrl));
                        }
                    } catch (Exception retryEx) {
                        log.warn("relay_query_retry_failed relay={} voucherId={} error={}", relayUrl, voucherId, retryEx.getMessage());
                    }
                } else {
                    log.warn("relay_query failed relay={} voucherId={} error={}", relayUrl, voucherId, e.getMessage());
                }
            }
        }

        return Optional.empty();
    }

    @Override
    public List<RelayEvent> fetchVouchersBatch(java.util.Collection<String> voucherIds) {
        if (voucherIds == null || voucherIds.isEmpty()) {
            return List.of();
        }

        List<RelayEvent> results = new ArrayList<>();
        Set<String> seenEventIds = new HashSet<>();
        Set<String> remainingIds = new HashSet<>(voucherIds);

        for (String relayUrl : relayUrls) {
            if (remainingIds.isEmpty()) {
                break;
            }

            try {
                ClientContext context = getOrReconnectClient(relayUrl);
                List<RelayEvent> relayResults = queryBatchForRelay(context.client(), relayUrl, remainingIds);
                for (RelayEvent event : relayResults) {
                    if (event.event().getId() != null && seenEventIds.add(event.event().getId())) {
                        results.add(event);
                        // Extract voucher ID from d-tag and remove from remaining
                        extractVoucherId(event.event()).ifPresent(remainingIds::remove);
                    }
                }
            } catch (Exception e) {
                if (isClosedSessionError(e)) {
                    log.info("relay_connection_closed relay={} reconnecting", relayUrl);
                    closeAndRemoveClient(relayUrl);
                    try {
                        ClientContext newContext = getOrReconnectClient(relayUrl);
                        List<RelayEvent> relayResults = queryBatchForRelay(newContext.client(), relayUrl, remainingIds);
                        for (RelayEvent event : relayResults) {
                            if (event.event().getId() != null && seenEventIds.add(event.event().getId())) {
                                results.add(event);
                                extractVoucherId(event.event()).ifPresent(remainingIds::remove);
                            }
                        }
                    } catch (Exception retryEx) {
                        log.warn("relay_batch_query_retry_failed relay={} error={}", relayUrl, retryEx.getMessage());
                    }
                } else {
                    log.warn("relay_batch_query_failed relay={} error={}", relayUrl, e.getMessage());
                }
            }
        }

        log.debug("batch_fetch_complete requested={} found={}", voucherIds.size(), results.size());
        return results;
    }

    private List<RelayEvent> queryBatchForRelay(
            SpringWebSocketClient client,
            String relayUrl,
            Set<String> voucherIds
    ) throws InterruptedException, IOException {
        String subscriptionId = "batch-" + UUID.randomUUID().toString().substring(0, 8);
        CountDownLatch eoseLatch = new CountDownLatch(1);
        List<RelayEvent> found = new ArrayList<>();

        // Build filter with multiple d-tags
        Filters filters = buildBatchFilters(voucherIds);
        ReqMessage reqMessage = new ReqMessage(subscriptionId, List.of(filters));

        client.subscribe(
                reqMessage,
                message -> {
                    GenericEvent event = parseEventFromJson(message);
                    if (event != null) {
                        found.add(new RelayEvent(event, relayUrl));
                    } else if (message.contains("\"EOSE\"")) {
                        eoseLatch.countDown();
                    }
                },
                error -> {
                    log.warn("relay_batch_query_error relay={} subscription={} error={}",
                            relayUrl, subscriptionId, error.getMessage());
                    eoseLatch.countDown();
                },
                eoseLatch::countDown
        );

        boolean completed = eoseLatch.await(queryTimeout.toMillis(), TimeUnit.MILLISECONDS);
        client.send(new CloseMessage(subscriptionId));
        if (!completed) {
            log.debug("relay_query_timeout subscription={}", subscriptionId);
        }
        return found;
    }

    private Filters buildBatchFilters(Set<String> voucherIds) {
        List<Filterable> filterables = new ArrayList<>();
        filterables.add(new KindFilter<>(nostr.base.Kind.valueOf(VOUCHER_KIND)));

        // Add multiple d-tag filters - Nostr protocol supports OR within a single filter
        for (String voucherId : voucherIds) {
            String dTagValue = voucherId.startsWith(D_TAG_PREFIX) ? voucherId : D_TAG_PREFIX + voucherId;
            filterables.add(new IdentifierTagFilter<>(new nostr.event.tag.IdentifierTag(dTagValue)));
        }

        Filters filters = new Filters(filterables);
        filters.setLimit(voucherIds.size());
        return filters;
    }

    private Optional<String> extractVoucherId(GenericEvent event) {
        if (event.getTags() == null) {
            return Optional.empty();
        }
        for (BaseTag tag : event.getTags()) {
            if (tag instanceof GenericTag genericTag && "d".equals(genericTag.getCode())) {
                List<ElementAttribute> attrs = genericTag.getAttributes();
                if (attrs != null && !attrs.isEmpty()) {
                    Object val = attrs.getFirst().value();
                    if (val != null) {
                        String dTag = val.toString();
                        // Remove the prefix if present
                        if (dTag.startsWith(D_TAG_PREFIX)) {
                            return Optional.of(dTag.substring(D_TAG_PREFIX.length()));
                        }
                        return Optional.of(dTag);
                    }
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<RelayEvent> searchChildren(String parentVoucherId, int limit) {
        Objects.requireNonNull(parentVoucherId, "parentVoucherId");
        if (parentVoucherId.isBlank()) {
            throw new IllegalArgumentException("parentVoucherId cannot be blank");
        }

        List<RelayEvent> results = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (String relayUrl : relayUrls) {
            try {
                ClientContext context = getOrReconnectClient(relayUrl);
                queryChildrenWithReconnect(context, relayUrl, parentVoucherId, limit, seenIds, results);
            } catch (Exception e) {
                if (isClosedSessionError(e)) {
                    log.info("relay_connection_closed relay={} reconnecting", relayUrl);
                    closeAndRemoveClient(relayUrl);
                    try {
                        ClientContext newContext = getOrReconnectClient(relayUrl);
                        queryChildrenWithReconnect(newContext, relayUrl, parentVoucherId, limit, seenIds, results);
                    } catch (Exception retryEx) {
                        log.warn("relay_child_query_retry_failed relay={} parentId={} error={}", relayUrl, parentVoucherId, retryEx.getMessage());
                    }
                } else {
                    log.warn("relay_child_query_failed relay={} parentId={} error={}", relayUrl, parentVoucherId, e.getMessage());
                }
            }
        }
        return results;
    }

    private void queryChildrenWithReconnect(ClientContext context, String relayUrl, String parentVoucherId, int limit, Set<String> seenIds, List<RelayEvent> results)
            throws InterruptedException, IOException {
        queryChildrenForRelay(context.client(), relayUrl, parentVoucherId, limit)
                .forEach(event -> {
                    if (event.event().getId() != null && seenIds.add(event.event().getId())) {
                        results.add(event);
                    }
                });
    }

    @Override
    public List<RelayEvent> fetchVoucherEvents(String voucherId, int limit) {
        Objects.requireNonNull(voucherId, "voucherId");
        int effectiveLimit = Math.max(1, limit);
        List<RelayEvent> results = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (String relayUrl : relayUrls) {
            try {
                ClientContext context = getOrReconnectClient(relayUrl);
                queryVoucherEventsWithReconnect(context, relayUrl, voucherId, effectiveLimit, seenIds, results);
            } catch (Exception e) {
                if (isClosedSessionError(e)) {
                    log.info("relay_connection_closed relay={} reconnecting", relayUrl);
                    closeAndRemoveClient(relayUrl);
                    try {
                        ClientContext newContext = getOrReconnectClient(relayUrl);
                        queryVoucherEventsWithReconnect(newContext, relayUrl, voucherId, effectiveLimit, seenIds, results);
                    } catch (Exception retryEx) {
                        log.warn("relay_history_query_retry_failed relay={} voucherId={} error={}", relayUrl, voucherId, retryEx.getMessage());
                    }
                } else {
                    log.warn("relay_history_query_failed relay={} voucherId={} error={}", relayUrl, voucherId, e.getMessage());
                }
            }
        }
        return results;
    }

    private void queryVoucherEventsWithReconnect(ClientContext context, String relayUrl, String voucherId, int limit, Set<String> seenIds, List<RelayEvent> results)
            throws InterruptedException, IOException {
        queryVoucherEventsForRelay(context.client(), relayUrl, voucherId, limit)
                .forEach(event -> {
                    if (event.event().getId() != null && seenIds.add(event.event().getId())) {
                        results.add(event);
                    }
                });
    }

    @Override
    public List<RelayEvent> searchVouchers(int limit) {
        int effectiveLimit = Math.max(1, limit);
        List<RelayEvent> results = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (String relayUrl : relayUrls) {
            try {
                ClientContext context = getOrReconnectClient(relayUrl);
                querySearchVouchersWithReconnect(context, relayUrl, effectiveLimit, seenIds, results);
            } catch (Exception e) {
                if (isClosedSessionError(e)) {
                    log.info("relay_connection_closed relay={} reconnecting", relayUrl);
                    closeAndRemoveClient(relayUrl);
                    try {
                        ClientContext newContext = getOrReconnectClient(relayUrl);
                        querySearchVouchersWithReconnect(newContext, relayUrl, effectiveLimit, seenIds, results);
                    } catch (Exception retryEx) {
                        log.warn("relay_search_query_retry_failed relay={} error={}", relayUrl, retryEx.getMessage());
                    }
                } else {
                    log.warn("relay_search_query_failed relay={} error={}", relayUrl, e.getMessage());
                }
            }
        }
        return results;
    }

    private void querySearchVouchersWithReconnect(ClientContext context, String relayUrl, int limit, Set<String> seenIds, List<RelayEvent> results)
            throws InterruptedException, IOException {
        queryAllVouchersForRelay(context.client(), relayUrl, limit)
                .forEach(event -> {
                    if (event.event().getId() != null && seenIds.add(event.event().getId())) {
                        results.add(event);
                    }
                });
    }

    /**
     * Gets an existing client or creates a new one if not present.
     */
    private ClientContext getOrReconnectClient(String relayUrl) {
        return clients.computeIfAbsent(relayUrl, this::createClient);
    }

    /**
     * Checks if the exception indicates a closed WebSocket session.
     */
    private boolean isClosedSessionError(Exception e) {
        String message = e.getMessage();
        if (message != null && message.contains("WebSocket session is closed")) {
            return true;
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            String causeMsg = cause.getMessage();
            if (causeMsg != null && causeMsg.contains("WebSocket session is closed")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public void disconnect() {
        clients.values().forEach(ClientContext::close);
        clients.clear();
    }

    /**
     * Closes and removes a client from the map, ensuring proper resource cleanup.
     */
    private void closeAndRemoveClient(String relayUrl) {
        ClientContext oldContext = clients.remove(relayUrl);
        if (oldContext != null) {
            oldContext.close();
        }
    }

    private Optional<GenericEvent> querySingleRelay(SpringWebSocketClient client, String relayUrl, String voucherId)
            throws InterruptedException, IOException {
        String subscriptionId = "voucher-" + UUID.randomUUID().toString().substring(0, 8);
        CountDownLatch eoseLatch = new CountDownLatch(1);
        AtomicReference<GenericEvent> found = new AtomicReference<>();

        Filters filters = buildFilters(voucherId);
        ReqMessage reqMessage = new ReqMessage(subscriptionId, List.of(filters));

        client.subscribe(
                reqMessage,
                message -> handleMessage(message, found, eoseLatch),
                error -> log.warn("relay_query_error relay={} subscription={} error={}", relayUrl, subscriptionId,
                        error.getMessage()),
                () -> eoseLatch.countDown()
        );

        boolean completed = eoseLatch.await(queryTimeout.toMillis(), TimeUnit.MILLISECONDS);
        client.send(new CloseMessage(subscriptionId));

        if (!completed) {
            log.debug("relay_query timeout relay={} subscription={}", relayUrl, subscriptionId);
        }

        return Optional.ofNullable(found.get());
    }

    private List<RelayEvent> queryChildrenForRelay(
            SpringWebSocketClient client,
            String relayUrl,
            String parentVoucherId,
            int limit
    ) throws InterruptedException, IOException {
        String subscriptionId = "child-" + UUID.randomUUID().toString().substring(0, 8);
        CountDownLatch eoseLatch = new CountDownLatch(1);
        List<RelayEvent> found = new ArrayList<>();

        Filters filters = buildChildFilters(limit);
        ReqMessage reqMessage = new ReqMessage(subscriptionId, List.of(filters));

        client.subscribe(
                reqMessage,
                message -> {
                    GenericEvent event = parseEventFromJson(message);
                    if (event != null && hasParentTag(event, parentVoucherId)) {
                        found.add(new RelayEvent(event, relayUrl));
                    } else if (message.contains("\"EOSE\"")) {
                        eoseLatch.countDown();
                    }
                },
                error -> {
                    log.warn("relay_child_query_error relay={} subscription={} error={}", relayUrl, subscriptionId, error.getMessage());
                    eoseLatch.countDown();
                },
                eoseLatch::countDown
        );

        boolean completed = eoseLatch.await(queryTimeout.toMillis(), TimeUnit.MILLISECONDS);
        client.send(new CloseMessage(subscriptionId));
        if (!completed) {
            log.debug("relay_query_timeout subscription={}", subscriptionId);
        }
        return found;
    }

    private List<RelayEvent> queryVoucherEventsForRelay(
            SpringWebSocketClient client,
            String relayUrl,
            String voucherId,
            int limit
    ) throws InterruptedException, IOException {
        String subscriptionId = "hist-" + UUID.randomUUID().toString().substring(0, 8);
        CountDownLatch eoseLatch = new CountDownLatch(1);
        List<RelayEvent> found = new ArrayList<>();

        Filters filters = buildFilters(voucherId);
        filters.setLimit(limit);
        ReqMessage reqMessage = new ReqMessage(subscriptionId, List.of(filters));

        client.subscribe(
                reqMessage,
                message -> {
                    GenericEvent event = parseEventFromJson(message);
                    if (event != null) {
                        found.add(new RelayEvent(event, relayUrl));
                    } else if (message.contains("\"EOSE\"")) {
                        eoseLatch.countDown();
                    }
                },
                error -> {
                    log.warn("relay_history_query_error relay={} subscription={} error={}", relayUrl, subscriptionId, error.getMessage());
                    eoseLatch.countDown();
                },
                eoseLatch::countDown
        );

        boolean completed = eoseLatch.await(queryTimeout.toMillis(), TimeUnit.MILLISECONDS);
        client.send(new CloseMessage(subscriptionId));
        if (!completed) {
            log.debug("relay_query_timeout subscription={}", subscriptionId);
        }
        return found;
    }

    private List<RelayEvent> queryAllVouchersForRelay(
            SpringWebSocketClient client,
            String relayUrl,
            int limit
    ) throws InterruptedException, IOException {
        String subscriptionId = "search-" + UUID.randomUUID().toString().substring(0, 8);
        CountDownLatch eoseLatch = new CountDownLatch(1);
        List<RelayEvent> found = new ArrayList<>();

        Filters filters = buildKindOnlyFilters(limit);
        ReqMessage reqMessage = new ReqMessage(subscriptionId, List.of(filters));

        client.subscribe(
                reqMessage,
                message -> {
                    GenericEvent event = parseEventFromJson(message);
                    if (event != null) {
                        found.add(new RelayEvent(event, relayUrl));
                    } else if (message.contains("\"EOSE\"")) {
                        eoseLatch.countDown();
                    }
                },
                error -> {
                    log.warn("relay_search_query_error relay={} subscription={} error={}", relayUrl, subscriptionId, error.getMessage());
                    eoseLatch.countDown();
                },
                eoseLatch::countDown
        );

        boolean completed = eoseLatch.await(queryTimeout.toMillis(), TimeUnit.MILLISECONDS);
        client.send(new CloseMessage(subscriptionId));
        if (!completed) {
            log.debug("relay_query_timeout subscription={}", subscriptionId);
        }
        return found;
    }

    private void handleMessage(String message, AtomicReference<GenericEvent> found, CountDownLatch eoseLatch) {
        if (message.contains("\"EVENT\"")) {
            GenericEvent event = parseEventFromJson(message);
            if (event != null && found.compareAndSet(null, event)) {
                eoseLatch.countDown();
            }
        } else if (message.contains("\"EOSE\"")) {
            eoseLatch.countDown();
        }
    }

    private Filters buildFilters(String voucherId) {
        List<Filterable> filterables = new ArrayList<>();
        filterables.add(new KindFilter<>(nostr.base.Kind.valueOf(VOUCHER_KIND)));
        // Add prefix to voucherId to match d-tag format: "voucher:<voucherId>"
        String dTagValue = voucherId.startsWith(D_TAG_PREFIX) ? voucherId : D_TAG_PREFIX + voucherId;
        filterables.add(new IdentifierTagFilter<>(new nostr.event.tag.IdentifierTag(dTagValue)));
        Filters filters = new Filters(filterables);
        filters.setLimit(1);
        return filters;
    }

    private Filters buildChildFilters(int limit) {
        List<Filterable> filterables = new ArrayList<>();
        filterables.add(new KindFilter<>(nostr.base.Kind.valueOf(VOUCHER_KIND)));
        Filters filters = new Filters(filterables);
        filters.setLimit(Math.max(1, limit));
        return filters;
    }

    private Filters buildKindOnlyFilters(int limit) {
        List<Filterable> filterables = new ArrayList<>();
        filterables.add(new KindFilter<>(nostr.base.Kind.valueOf(VOUCHER_KIND)));
        Filters filters = new Filters(filterables);
        filters.setLimit(Math.max(1, limit));
        return filters;
    }

    private ClientContext createClient(String relayUrl) {
        long awaitTimeoutMs = queryTimeout.toMillis();
        long pollIntervalMs = 500L;
        WebSocketClientIF webSocketClient = null;
        try {
            webSocketClient = new StandardWebSocketClient(relayUrl, awaitTimeoutMs, pollIntervalMs);
            SpringWebSocketClient client = new SpringWebSocketClient(webSocketClient, relayUrl);
            return new ClientContext(webSocketClient, client);
        } catch (Exception e) {
            // Close the raw WebSocket client if SpringWebSocketClient creation failed
            if (webSocketClient != null) {
                try {
                    webSocketClient.close();
                } catch (Exception closeEx) {
                    log.debug("websocket_cleanup_failed relay={} error={}", relayUrl, closeEx.getMessage());
                }
            }
            throw new IllegalStateException("Failed to create WebSocket client for relay: " + relayUrl, e);
        }
    }

    private GenericEvent parseEventFromJson(String jsonMessage) {
        try {
            JsonNode root = JSON_MAPPER.readTree(jsonMessage);
            if (!root.isArray() || root.size() < 3 || !"EVENT".equals(root.get(0).asText())) {
                return null;
            }

            JsonNode eventNode = root.get(2);
            GenericEvent event = new GenericEvent();

            if (eventNode.has("id")) {
                event.setId(eventNode.get("id").asText());
            }
            if (eventNode.has("pubkey")) {
                event.setPubKey(new PublicKey(eventNode.get("pubkey").asText()));
            }
            if (eventNode.has("kind")) {
                event.setKind(eventNode.get("kind").asInt());
            }
            if (eventNode.has("content")) {
                event.setContent(eventNode.get("content").asText());
            }
            if (eventNode.has("created_at")) {
                event.setCreatedAt(eventNode.get("created_at").asLong());
            }
            if (eventNode.has("sig")) {
                event.setSignature(nostr.base.Signature.fromString(eventNode.get("sig").asText()));
            }

            if (eventNode.has("tags")) {
                List<BaseTag> tags = new ArrayList<>();
                for (JsonNode tagArray : eventNode.get("tags")) {
                    if (tagArray.isArray() && tagArray.size() > 0) {
                        String tagCode = tagArray.get(0).asText();
                        List<nostr.base.ElementAttribute> attrs = new ArrayList<>();
                        // Start from index 1 - index 0 is the tag code, not an attribute
                        for (int i = 1; i < tagArray.size(); i++) {
                            attrs.add(new nostr.base.ElementAttribute(null, tagArray.get(i).asText()));
                        }
                        tags.add(new GenericTag(tagCode, attrs));
                    }
                }
                event.setTags(tags);
            }

            return event;
        } catch (Exception e) {
            log.warn("relay_query parse_event_failed error={}", e.getMessage());
            return null;
        }
    }

    private boolean hasParentTag(GenericEvent event, String parentVoucherId) {
        if (event.getTags() == null) {
            return false;
        }
        for (BaseTag tag : event.getTags()) {
            if (tag instanceof GenericTag genericTag && "parent".equals(genericTag.getCode())) {
                List<ElementAttribute> attrs = genericTag.getAttributes();
                if (attrs != null && !attrs.isEmpty()) {
                    Object val = attrs.getFirst().value();
                    if (val != null && parentVoucherId.equals(val.toString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private record ClientContext(WebSocketClientIF rawClient, SpringWebSocketClient client) implements AutoCloseable {
        @Override
        public void close() {
            try {
                client.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}
