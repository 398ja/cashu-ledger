package xyz.tcheeric.cashu.ledger.core.service;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.ledger.core.mapper.VoucherEventMapper;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.trace.IssuerAttestationConfig;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;
import xyz.tcheeric.cashu.ledger.core.model.TraversalDirection;
import xyz.tcheeric.cashu.ledger.core.relay.RelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.state.StatusChange;
import xyz.tcheeric.cashu.ledger.core.state.VoucherSearchCriteria;
import xyz.tcheeric.cashu.ledger.core.state.VoucherStateSnapshot;
import xyz.tcheeric.cashu.ledger.core.state.VoucherStateJournal;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;
import xyz.tcheeric.cashu.ledger.core.state.UnclaimedStatusResult;
import xyz.tcheeric.cashu.ledger.core.state.ReclaimOutcome;
import xyz.tcheeric.cashu.ledger.core.state.VerificationReport;
import xyz.tcheeric.cashu.ledger.core.state.ValueConservationVerifier;
import xyz.tcheeric.cashu.ledger.core.state.HistoryResult;
import nostr.crypto.schnorr.Schnorr;
import nostr.crypto.schnorr.SchnorrException;
import nostr.base.PublicKey;
import nostr.base.Signature;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Default implementation for fetching vouchers via Nostr relays.
 */
@Slf4j
public class VoucherLedgerServiceImpl implements VoucherLedgerService {

    private final RelayConnectionManager relayConnectionManager;
    private final VoucherEventMapper mapper;
    private final Map<String, CacheEntry<VoucherNode>> voucherCache = new ConcurrentHashMap<>();
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);
    private final Duration cacheTtl;

    /**
     * The issuer keys this ledger will attest to. Empty by default, in which case a signature is
     * reported as untrusted rather than valid: with no registered key there is nothing to check
     * it against, and answering "valid" to that question is how a forged voucher passed.
     */
    private final IssuerAttestationConfig issuerAttestation;

    public VoucherLedgerServiceImpl(
            RelayConnectionManager relayConnectionManager,
            List<String> relayUrls,
            Duration connectionTimeout,
            Duration queryTimeout
    ) {
        this(relayConnectionManager, relayUrls, connectionTimeout, queryTimeout, DEFAULT_CACHE_TTL);
    }

    public VoucherLedgerServiceImpl(
            RelayConnectionManager relayConnectionManager,
            List<String> relayUrls,
            Duration connectionTimeout,
            Duration queryTimeout,
            Duration cacheTtl
    ) {
        this(relayConnectionManager, relayUrls, connectionTimeout, queryTimeout, cacheTtl,
                IssuerAttestationConfig.empty());
    }

    /**
     * @param issuerAttestation the issuer keys this ledger will attest to; pass a populated
     *                          config to have {@code verify()} report signatures as trusted
     */
    public VoucherLedgerServiceImpl(
            RelayConnectionManager relayConnectionManager,
            List<String> relayUrls,
            Duration connectionTimeout,
            Duration queryTimeout,
            Duration cacheTtl,
            IssuerAttestationConfig issuerAttestation
    ) {
        this.issuerAttestation = issuerAttestation != null
                ? issuerAttestation : IssuerAttestationConfig.empty();
        this.relayConnectionManager = Objects.requireNonNull(relayConnectionManager, "relayConnectionManager");
        this.mapper = new VoucherEventMapper();
        this.cacheTtl = cacheTtl != null ? cacheTtl : DEFAULT_CACHE_TTL;

        Duration effectiveQueryTimeout = queryTimeout != null ? queryTimeout : Duration.ofSeconds(30);
        Duration connectTimeout = connectionTimeout != null ? connectionTimeout : effectiveQueryTimeout;
        this.relayConnectionManager.connect(relayUrls, connectTimeout);
    }

    @Override
    public Optional<VoucherNode> fetchVoucher(String voucherId) {
        Objects.requireNonNull(voucherId, "voucherId");
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID cannot be blank");
        }

        Optional<VoucherNode> cached = readFromCache(voucherId);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<RelayConnectionManager.RelayEvent> relayEvent = relayConnectionManager.fetchVoucher(voucherId);
        if (relayEvent.isEmpty()) {
            log.info("voucher_fetch not_found voucher_id={}", voucherId);
            return Optional.empty();
        }

        Optional<VoucherNode> mapped = mapper.toVoucher(relayEvent.get().event(), relayEvent.get().relayUrl());
        mapped.ifPresent(node -> voucherCache.put(voucherId, CacheEntry.of(node, cacheTtl)));
        return mapped;
    }

    @Override
    public Optional<VoucherTree> buildTree(String voucherId, int maxDepth, TraversalDirection direction) {
        Objects.requireNonNull(voucherId, "voucherId");
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID cannot be blank");
        }
        TraversalDirection effectiveDirection = direction != null ? direction : TraversalDirection.BOTH;
        int depthLimit = Math.max(1, maxDepth);

        Map<String, VoucherNode> nodes = new LinkedHashMap<>();
        Optional<VoucherNode> cached = readFromCache(voucherId);
        if (cached.isPresent()) {
            nodes.put(cached.get().voucherId(), cached.get());
        } else {
            Optional<RelayConnectionManager.RelayEvent> relayEvent = relayConnectionManager.fetchVoucher(voucherId);
            if (relayEvent.isEmpty()) {
                log.info("voucher_tree target_not_found voucher_id={}", voucherId);
                return Optional.empty();
            }

            Optional<VoucherNode> mappedTarget = mapper.toVoucher(relayEvent.get().event(), relayEvent.get().relayUrl());
            mappedTarget.ifPresent(node -> voucherCache.put(node.voucherId(), CacheEntry.of(node, cacheTtl)));
            if (mappedTarget.isEmpty()) {
                return Optional.empty();
            }
            nodes.put(mappedTarget.get().voucherId(), mappedTarget.get());
        }

        VoucherNode mappedTargetNode = nodes.get(voucherId);

        if (mappedTargetNode == null) {
            log.info("voucher_tree target_not_found voucher_id={}", voucherId);
            return Optional.empty();
        }

        if (effectiveDirection == TraversalDirection.UP || effectiveDirection == TraversalDirection.BOTH) {
            traverseUp(mappedTargetNode, nodes, depthLimit);
        }
        if (effectiveDirection == TraversalDirection.DOWN || effectiveDirection == TraversalDirection.BOTH) {
            traverseDown(mappedTargetNode, nodes, depthLimit);
        }

        Map<String, List<String>> childrenMap = buildChildrenMap(nodes);
        VoucherNode root = findRoot(nodes);
        String depthStartId = root != null ? root.voucherId() : mappedTargetNode.voucherId();
        int computedDepth = computeDepth(depthStartId, childrenMap);

        VoucherTree tree = new VoucherTree(root, mappedTargetNode, nodes, childrenMap, computedDepth, nodes.size());
        return Optional.of(tree);
    }

    @Override
    public void close() {
        relayConnectionManager.close();
    }

    @Override
    public HistoryResult fetchHistory(String voucherId, Instant since, Instant until, int limit) {
        Objects.requireNonNull(voucherId, "voucherId");
        int effectiveLimit = Math.max(1, limit);
        List<RelayConnectionManager.RelayEvent> events =
                relayConnectionManager.fetchVoucherEvents(voucherId, effectiveLimit);

        VoucherStateJournal journal = new VoucherStateJournal();
        List<StatusChange> history = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (RelayConnectionManager.RelayEvent event : events) {
            mapper.toVoucher(event.event(), event.relayUrl())
                    .filter(node -> isWithinRange(node, since, until))
                    .ifPresent(node -> {
                        VoucherStateJournal.StateDecision decision = journal.record(node);
                        if (decision.decision() == VoucherStateJournal.StateDecisionType.ACCEPTED) {
                            VoucherStateSnapshot snapshot = VoucherStateSnapshot.fromNode(node);
                            history.add(new StatusChange(
                                    node.voucherId(),
                                    node.status(),
                                    snapshot.previousStatus(),
                                    snapshot.stateVersion(),
                                    snapshot.transitionAt(),
                                    snapshot.createdAt(),
                                    node.eventMetadata() != null ? node.eventMetadata().relay() : null,
                                    node.eventMetadata() != null ? node.eventMetadata().eventId() : null,
                                    node.stateMetadata() != null ? node.stateMetadata().transitionActor() : null
                            ));
                        } else {
                            warnings.add(decision.message());
                        }
                    });
        }

        history.sort((a, b) -> {
            int versionCompare = Long.compare(a.stateVersion(), b.stateVersion());
            if (versionCompare != 0) {
                return versionCompare;
            }
            Instant ta = a.transitionAt();
            Instant tb = b.transitionAt();
            if (ta != null && tb != null) {
                return ta.compareTo(tb);
            }
            if (ta == null && tb != null) {
                return -1;
            }
            if (ta != null) {
                return 1;
            }
            return 0;
        });
        return new HistoryResult(history, warnings);
    }

    @Override
    public List<VoucherNode> search(VoucherSearchCriteria criteria) {
        VoucherSearchCriteria effectiveCriteria = criteria != null ? criteria : VoucherSearchCriteria.empty();
        int limit = effectiveCriteria.limit();

        List<RelayConnectionManager.RelayEvent> events = relayConnectionManager.searchVouchers(limit);
        VoucherStateJournal journal = new VoucherStateJournal();
        Map<String, VoucherNode> latestNodes = new HashMap<>();

        for (RelayConnectionManager.RelayEvent event : events) {
            mapper.toVoucher(event.event(), event.relayUrl())
                    .ifPresent(node -> {
                        VoucherStateJournal.StateDecision decision = journal.record(node);
                        if (decision.decision() == VoucherStateJournal.StateDecisionType.ACCEPTED) {
                            latestNodes.put(node.voucherId(), node);
                        }
                    });
        }

        return latestNodes.values().stream()
                .filter(node -> matchesCriteria(node, effectiveCriteria))
                .filter(node -> !effectiveCriteria.unclaimed() || node.status() == VoucherStatus.ISSUED)
                .limit(limit)
                .toList();
    }

    @Override
    public List<VoucherNode> listUnclaimed(String sentBy, int limit) {
        VoucherSearchCriteria criteria = new VoucherSearchCriteria(
                null,
                VoucherStatus.ISSUED,
                null,
                null,
                limit,
                true
        );
        List<VoucherNode> results = search(criteria);
        if (sentBy == null || sentBy.isBlank()) {
            return results;
        }
        return results.stream()
                .filter(node -> node.eventMetadata() != null && sentBy.equalsIgnoreCase(node.eventMetadata().pubkey()))
                .toList();
    }

    @Override
    public UnclaimedStatusResult checkUnclaimedStatus(String voucherId, String tokenFilePath) {
        Objects.requireNonNull(voucherId, "voucherId");
        // Mint connectivity not yet implemented
        return UnclaimedStatusResult.notSupported(voucherId);
    }

    @Override
    public ReclaimOutcome reclaim(String voucherId, String tokenFilePath) {
        Objects.requireNonNull(voucherId, "voucherId");
        // Mint connectivity not yet implemented
        return ReclaimOutcome.notSupported(voucherId);
    }

    @Override
    public VerificationReport verify(String voucherId) {
        Optional<VoucherTree> treeOpt = buildTree(voucherId, 10, TraversalDirection.BOTH);
        if (treeOpt.isEmpty()) {
            return VerificationReport.notFound(voucherId);
        }
        VoucherTree tree = treeOpt.get();
        VoucherNode target = tree.target();
        boolean signatureValid = verifySignature(target);
        boolean valueConserved = ValueConservationVerifier.isConserved(target, tree);
        boolean hierarchyComplete = tree.root() != null && !tree.nodes().isEmpty();
        boolean stateTransitionsValid = true;
        List<String> auditIssues = new ArrayList<>();

        List<RelayConnectionManager.RelayEvent> events =
                relayConnectionManager.fetchVoucherEvents(voucherId, 200);
        VoucherStateJournal journal = new VoucherStateJournal();
        for (RelayConnectionManager.RelayEvent event : events) {
            mapper.toVoucher(event.event(), event.relayUrl()).ifPresent(node -> {
                VoucherStateJournal.StateDecision decision = journal.record(node);
                if (decision.decision() == VoucherStateJournal.StateDecisionType.REJECTED_INVALID) {
                    auditIssues.add(decision.message());
                }
            });
        }
        if (!auditIssues.isEmpty()) {
            stateTransitionsValid = false;
        }

        return new VerificationReport(
                voucherId,
                true,
                signatureValid,
                valueConserved,
                hierarchyComplete,
                stateTransitionsValid,
                String.join("; ", auditIssues),
                auditIssues
        );
    }

    private boolean verifySignature(VoucherNode node) {
        if (node.eventMetadata() == null || node.eventMetadata().signatureHex() == null
                || node.eventMetadata().eventId() == null || node.issuerPublicKey() == null) {
            return false;
        }

        // Bind the signing key to the claimed issuer before the signature means anything.
        //
        // Checking the signature against node.issuerPublicKey() alone asks only whether the
        // event is internally consistent: the key comes from the event, so anyone who can write
        // to a relay can supply a key they hold, claim any issuer id, and be pronounced valid.
        // An unregistered issuer is reported unverified rather than valid; that is the honest
        // answer when there is no key to check against.
        if (!issuerAttestation.isAuthorised(node.issuerId(), node.issuerPublicKey())) {
            if (issuerAttestation.isEmpty()) {
                log.warn("signature_untrusted_no_issuer_registry voucher_id={} issuer_id={} "
                        + "(configure IssuerAttestationConfig to attest issuer keys)",
                        node.voucherId(), node.issuerId());
            } else {
                log.warn("signature_untrusted_unregistered_key voucher_id={} issuer_id={}",
                        node.voucherId(), node.issuerId());
            }
            return false;
        }

        try {
            byte[] message = hexToBytes(node.eventMetadata().eventId());
            Signature signature = Signature.fromString(node.eventMetadata().signatureHex());
            PublicKey pubKey = new PublicKey(node.issuerPublicKey());
            return Schnorr.verify(message, pubKey.getRawData(), signature.getRawData());
        } catch (SchnorrException | IllegalArgumentException e) {
            log.warn("signature_verify_failed voucher_id={} error={}", node.voucherId(), e.getMessage());
            return false;
        }
    }

    private byte[] hexToBytes(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        String normalized = hex.length() % 2 == 0 ? hex : "0" + hex;
        int len = normalized.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(normalized.charAt(i), 16) << 4)
                    + Character.digit(normalized.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Traverses up the voucher tree using BFS with batch prefetching.
     *
     * <p>Collects parent IDs at each level and fetches them in batch,
     * reducing round-trip latency compared to individual fetches.
     */
    private void traverseUp(VoucherNode node, Map<String, VoucherNode> nodes, int remainingDepth) {
        if (remainingDepth <= 0 || node.parentContributions() == null) {
            return;
        }

        // BFS with batch prefetching at each level
        Set<String> currentLevel = new HashSet<>();
        for (var parent : node.parentContributions()) {
            if (!nodes.containsKey(parent.parentVoucherId())) {
                currentLevel.add(parent.parentVoucherId());
            }
        }

        int depth = remainingDepth;
        while (!currentLevel.isEmpty() && depth > 0) {
            // Batch fetch all parents at current level
            List<RelayConnectionManager.RelayEvent> batchResults =
                    relayConnectionManager.fetchVouchersBatch(currentLevel);

            Set<String> nextLevel = new HashSet<>();
            for (RelayConnectionManager.RelayEvent relayEvent : batchResults) {
                Optional<VoucherNode> parentNodeOpt = mapper.toVoucher(relayEvent.event(), relayEvent.relayUrl());
                if (parentNodeOpt.isPresent()) {
                    VoucherNode parentNode = parentNodeOpt.get();
                    voucherCache.put(parentNode.voucherId(), CacheEntry.of(parentNode, cacheTtl));
                    nodes.put(parentNode.voucherId(), parentNode);

                    // Collect next level parents
                    if (parentNode.parentContributions() != null) {
                        for (var grandParent : parentNode.parentContributions()) {
                            if (!nodes.containsKey(grandParent.parentVoucherId())) {
                                nextLevel.add(grandParent.parentVoucherId());
                            }
                        }
                    }
                }
            }

            currentLevel = nextLevel;
            depth--;
        }
    }

    /**
     * Traverses down the voucher tree using BFS with batch prefetching.
     *
     * <p>Uses a level-by-level approach that:
     * 1. Searches for children via relay at each level
     * 2. Collects explicit splitInto references
     * 3. Batch fetches any missing split children
     * This reduces round-trip latency compared to individual fetches.
     */
    private void traverseDown(VoucherNode node, Map<String, VoucherNode> nodes, int remainingDepth) {
        if (remainingDepth <= 0) {
            return;
        }

        // BFS with batch prefetching at each level
        List<VoucherNode> currentLevel = new ArrayList<>();
        currentLevel.add(node);

        int depth = remainingDepth;
        while (!currentLevel.isEmpty() && depth > 0) {
            List<VoucherNode> nextLevel = new ArrayList<>();
            Set<String> discoveredChildIds = new HashSet<>();
            Set<String> splitChildIdsToFetch = new HashSet<>();

            // Process each node at current level
            for (VoucherNode currentNode : currentLevel) {
                // Search for children via relay
                List<RelayConnectionManager.RelayEvent> children =
                        relayConnectionManager.searchChildren(currentNode.voucherId(), 50);

                for (RelayConnectionManager.RelayEvent childEvent : children) {
                    Optional<VoucherNode> childNodeOpt = mapper.toVoucher(childEvent.event(), childEvent.relayUrl());
                    if (childNodeOpt.isPresent()) {
                        VoucherNode childNode = childNodeOpt.get();
                        if (!nodes.containsKey(childNode.voucherId())) {
                            voucherCache.put(childNode.voucherId(), CacheEntry.of(childNode, cacheTtl));
                            nodes.put(childNode.voucherId(), childNode);
                            nextLevel.add(childNode);
                        }
                        discoveredChildIds.add(childNode.voucherId());
                    }
                }

                // Collect explicit splitInto children that need to be fetched
                List<String> splitChildren = currentNode.stateMetadata() != null
                        ? currentNode.stateMetadata().splitInto()
                        : List.of();
                for (String splitChildId : splitChildren) {
                    if (!nodes.containsKey(splitChildId) && !discoveredChildIds.contains(splitChildId)) {
                        splitChildIdsToFetch.add(splitChildId);
                    }
                }
            }

            // Batch fetch missing split children
            if (!splitChildIdsToFetch.isEmpty()) {
                List<RelayConnectionManager.RelayEvent> batchResults =
                        relayConnectionManager.fetchVouchersBatch(splitChildIdsToFetch);

                for (RelayConnectionManager.RelayEvent relayEvent : batchResults) {
                    Optional<VoucherNode> splitChildOpt = mapper.toVoucher(relayEvent.event(), relayEvent.relayUrl());
                    if (splitChildOpt.isPresent()) {
                        VoucherNode splitChild = splitChildOpt.get();
                        if (!nodes.containsKey(splitChild.voucherId())) {
                            voucherCache.put(splitChild.voucherId(), CacheEntry.of(splitChild, cacheTtl));
                            nodes.put(splitChild.voucherId(), splitChild);
                            nextLevel.add(splitChild);
                        }
                    }
                }
            }

            currentLevel = nextLevel;
            depth--;
        }
    }

    private Map<String, List<String>> buildChildrenMap(Map<String, VoucherNode> nodes) {
        Map<String, List<String>> children = new HashMap<>();
        nodes.values().forEach(node -> {
            if (node.parentContributions() != null) {
                node.parentContributions().forEach(parent -> {
                    children.computeIfAbsent(parent.parentVoucherId(), key -> new ArrayList<>())
                            .add(node.voucherId());
                });
            }
            if (node.stateMetadata() != null && !node.stateMetadata().splitInto().isEmpty()) {
                node.stateMetadata().splitInto().forEach(childId ->
                        children.computeIfAbsent(node.voucherId(), key -> new ArrayList<>()).add(childId));
            }
        });
        return children;
    }

    private VoucherNode findRoot(Map<String, VoucherNode> nodes) {
        return nodes.values().stream()
                .filter(n -> n.parentContributions() == null || n.parentContributions().isEmpty())
                .findFirst()
                .orElse(nodes.values().iterator().next());
    }

    private int computeDepth(String targetId, Map<String, List<String>> childrenMap) {
        int depth = 0;
        Deque<String> queue = new ArrayDeque<>();
        queue.add(targetId);

        while (!queue.isEmpty()) {
            int levelSize = queue.size();
            depth++;
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                queue.addAll(childrenMap.getOrDefault(current, List.of()));
            }
        }
        return depth;
    }

    private boolean matchesCriteria(VoucherNode node, VoucherSearchCriteria criteria) {
        if (criteria.issuerId() != null && !criteria.issuerId().equals(node.issuerId())) {
            return false;
        }
        if (criteria.status() != null && node.status() != criteria.status()) {
            return false;
        }
        return isWithinRange(node, criteria.since(), criteria.until());
    }

    private boolean isWithinRange(VoucherNode node, Instant since, Instant until) {
        Instant issuedAt = node.issuedAt();
        if (since != null && issuedAt != null && issuedAt.isBefore(since)) {
            return false;
        }
        if (until != null && issuedAt != null && issuedAt.isAfter(until)) {
            return false;
        }
        return true;
    }

    private Optional<VoucherNode> readFromCache(String voucherId) {
        CacheEntry<VoucherNode> entry = voucherCache.get(voucherId);
        if (entry == null || entry.isExpired()) {
            voucherCache.remove(voucherId);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    private record CacheEntry<T>(T value, Instant expiresAt) {
        static <T> CacheEntry<T> of(T value, Duration ttl) {
            Instant expires = Instant.now().plus(ttl);
            return new CacheEntry<>(value, expires);
        }

        boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }
    }
}
