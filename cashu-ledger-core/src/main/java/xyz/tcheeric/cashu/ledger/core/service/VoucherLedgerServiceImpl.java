package xyz.tcheeric.cashu.ledger.core.service;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.ledger.core.mapper.VoucherEventMapper;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;
import xyz.tcheeric.cashu.ledger.core.model.TraversalDirection;
import xyz.tcheeric.cashu.ledger.core.relay.RelayConnectionManager;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default implementation for fetching vouchers via Nostr relays.
 */
@Slf4j
public class VoucherLedgerServiceImpl implements VoucherLedgerService {

    private final RelayConnectionManager relayConnectionManager;
    private final VoucherEventMapper mapper;

    public VoucherLedgerServiceImpl(
            RelayConnectionManager relayConnectionManager,
            List<String> relayUrls,
            Duration connectionTimeout,
            Duration queryTimeout
    ) {
        this.relayConnectionManager = Objects.requireNonNull(relayConnectionManager, "relayConnectionManager");
        this.mapper = new VoucherEventMapper();

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

        Optional<RelayConnectionManager.RelayEvent> relayEvent = relayConnectionManager.fetchVoucher(voucherId);
        if (relayEvent.isEmpty()) {
            log.info("voucher_fetch not_found voucher_id={}", voucherId);
            return Optional.empty();
        }

        return mapper.toVoucher(relayEvent.get().event(), relayEvent.get().relayUrl());
    }

    @Override
    public Optional<VoucherTree> buildTree(String voucherId, int maxDepth, TraversalDirection direction) {
        Objects.requireNonNull(voucherId, "voucherId");
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID cannot be blank");
        }
        TraversalDirection effectiveDirection = direction != null ? direction : TraversalDirection.BOTH;
        int depthLimit = Math.max(1, maxDepth);

        Optional<RelayConnectionManager.RelayEvent> relayEvent = relayConnectionManager.fetchVoucher(voucherId);
        if (relayEvent.isEmpty()) {
            log.info("voucher_tree target_not_found voucher_id={}", voucherId);
            return Optional.empty();
        }

        Optional<VoucherNode> mappedTarget = mapper.toVoucher(relayEvent.get().event(), relayEvent.get().relayUrl());
        if (mappedTarget.isEmpty()) {
            return Optional.empty();
        }

        Map<String, VoucherNode> nodes = new LinkedHashMap<>();
        nodes.put(mappedTarget.get().voucherId(), mappedTarget.get());

        if (effectiveDirection == TraversalDirection.UP || effectiveDirection == TraversalDirection.BOTH) {
            traverseUp(mappedTarget.get(), nodes, depthLimit);
        }
        if (effectiveDirection == TraversalDirection.DOWN || effectiveDirection == TraversalDirection.BOTH) {
            traverseDown(mappedTarget.get(), nodes, depthLimit);
        }

        Map<String, List<String>> childrenMap = buildChildrenMap(nodes);
        VoucherNode root = findRoot(nodes);
        String depthStartId = root != null ? root.voucherId() : mappedTarget.get().voucherId();
        int computedDepth = computeDepth(depthStartId, childrenMap);

        VoucherTree tree = new VoucherTree(root, mappedTarget.get(), nodes, childrenMap, computedDepth, nodes.size());
        return Optional.of(tree);
    }

    @Override
    public void close() {
        relayConnectionManager.close();
    }

    private void traverseUp(VoucherNode node, Map<String, VoucherNode> nodes, int remainingDepth) {
        if (remainingDepth <= 0 || node.parentContributions() == null) {
            return;
        }
        node.parentContributions().forEach(parent -> {
            if (!nodes.containsKey(parent.parentVoucherId())) {
                Optional<RelayConnectionManager.RelayEvent> relayEvent =
                        relayConnectionManager.fetchVoucher(parent.parentVoucherId());
                relayEvent.flatMap(re -> mapper.toVoucher(re.event(), re.relayUrl()))
                        .ifPresent(parentNode -> {
                            nodes.put(parentNode.voucherId(), parentNode);
                            traverseUp(parentNode, nodes, remainingDepth - 1);
                        });
            }
        });
    }

    private void traverseDown(VoucherNode node, Map<String, VoucherNode> nodes, int remainingDepth) {
        if (remainingDepth <= 0) {
            return;
        }
        List<RelayConnectionManager.RelayEvent> children = relayConnectionManager.searchChildren(node.voucherId(), 50);
        for (RelayConnectionManager.RelayEvent childEvent : children) {
            mapper.toVoucher(childEvent.event(), childEvent.relayUrl())
                    .ifPresent(childNode -> {
                        if (!nodes.containsKey(childNode.voucherId())) {
                            nodes.put(childNode.voucherId(), childNode);
                            traverseDown(childNode, nodes, remainingDepth - 1);
                        }
                    });
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
                childrenMap.getOrDefault(current, List.of()).forEach(queue::add);
            }
        }
        return depth;
    }
}
