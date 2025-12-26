package xyz.tcheeric.cashu.ledger.core.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record VoucherTree(
        VoucherNode root,
        VoucherNode target,
        Map<String, VoucherNode> nodes,
        Map<String, List<String>> childrenMap,
        int depth,
        int totalNodes
) {

    public List<VoucherNode> ancestors(String voucherId) {
        Objects.requireNonNull(voucherId, "voucherId");
        List<VoucherNode> result = new ArrayList<>();
        VoucherNode current = nodes.get(voucherId);
        while (current != null && current.parentContributions() != null && !current.parentContributions().isEmpty()) {
            String parentId = current.parentContributions().getFirst().parentVoucherId();
            VoucherNode parent = nodes.get(parentId);
            if (parent == null) {
                break;
            }
            result.add(parent);
            current = parent;
        }
        return result;
    }

    public List<VoucherNode> descendants(String voucherId) {
        Objects.requireNonNull(voucherId, "voucherId");
        List<VoucherNode> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(voucherId);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            List<String> children = childrenMap.getOrDefault(currentId, List.of());
            for (String childId : children) {
                VoucherNode child = nodes.get(childId);
                if (child != null) {
                    result.add(child);
                    queue.add(childId);
                }
            }
        }
        return result;
    }

    public List<VoucherNode> siblings(String voucherId) {
        Objects.requireNonNull(voucherId, "voucherId");
        VoucherNode node = nodes.get(voucherId);
        if (node == null || node.parentContributions() == null || node.parentContributions().isEmpty()) {
            return List.of();
        }
        String parentId = node.parentContributions().getFirst().parentVoucherId();
        return childrenMap.getOrDefault(parentId, List.of()).stream()
                .filter(id -> !id.equals(voucherId))
                .map(nodes::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public boolean isRoot(String voucherId) {
        VoucherNode node = nodes.get(voucherId);
        return node != null && (node.parentContributions() == null || node.parentContributions().isEmpty());
    }

    public boolean isLeaf(String voucherId) {
        return childrenMap.getOrDefault(voucherId, List.of()).isEmpty();
    }

    public Set<String> allVoucherIds() {
        return nodes.keySet();
    }
}
