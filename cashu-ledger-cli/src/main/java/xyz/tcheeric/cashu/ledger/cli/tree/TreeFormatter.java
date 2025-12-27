package xyz.tcheeric.cashu.ledger.cli.tree;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xyz.tcheeric.cashu.ledger.cli.inspect.OutputFormat;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;
import xyz.tcheeric.cashu.ledger.core.state.ValueConservationVerifier;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TreeFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OutputFormat format;

    TreeFormatter(String format) {
        this.format = OutputFormat.from(format);
    }

    String format(VoucherTree tree) {
        return switch (format) {
            case JSON -> formatJson(tree);
            case TREE, TEXT, CSV -> formatTree(tree);
        };
    }

    private String formatTree(VoucherTree tree) {
        StringBuilder sb = new StringBuilder();
        sb.append("Voucher Hierarchy for ").append(tree.target().voucherId()).append(System.lineSeparator());
        sb.append("Depth: ").append(tree.depth()).append(" | Total Nodes: ").append(tree.totalNodes()).append(System.lineSeparator());
        renderNode(sb, tree.target().voucherId(), tree, "", true, true, new HashSet<>());
        return sb.toString();
    }

    private void renderNode(
            StringBuilder sb,
            String voucherId,
            VoucherTree tree,
            String indent,
            boolean isLast,
            boolean isRoot,
            Set<String> visited
    ) {
        if (!visited.add(voucherId)) {
            sb.append(indent).append(isRoot ? "" : (isLast ? "└── " : "├── "))
                    .append("↺ ").append(voucherId).append(" (cycle)").append(System.lineSeparator());
            return;
        }

        VoucherNode node = tree.nodes().get(voucherId);
        if (node == null) {
            sb.append(indent).append(isRoot ? "" : (isLast ? "└── " : "├── "))
                    .append("◌ missing ").append(voucherId).append(System.lineSeparator());
            return;
        }

        String branch = isRoot ? "" : (isLast ? "└── " : "├── ");
        sb.append(indent).append(branch)
                .append("◉ ")
                .append(node.voucherId())
                .append(" [").append(node.faceValue()).append(", ").append(node.tokenAmount()).append("] ")
                .append(formatStatus(node));
        if (node.status() != null && node.status().name().equalsIgnoreCase("split")
                && !ValueConservationVerifier.isConserved(node, tree)) {
            sb.append(" ⚠ value drift");
        }
        if (tree.target().voucherId().equals(node.voucherId())) {
            sb.append(" ← target");
        }
        sb.append(System.lineSeparator());

        List<String> children = tree.childrenMap().getOrDefault(voucherId, List.of());
        String childIndent = indent + (isRoot ? "" : (isLast ? "    " : "│   "));
        for (int i = 0; i < children.size(); i++) {
            boolean last = i == children.size() - 1;
            renderNode(sb, children.get(i), tree, childIndent, last, false, visited);
        }
    }

    private String formatJson(VoucherTree tree) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("target", tree.target().voucherId());
        root.put("root", tree.root() != null ? tree.root().voucherId() : "");
        root.put("depth", tree.depth());
        root.put("totalNodes", tree.totalNodes());

        ArrayNode nodes = root.putArray("nodes");
        tree.nodes().values().forEach(node -> {
            ObjectNode n = nodes.addObject();
            n.put("voucherId", node.voucherId());
            n.put("status", formatStatus(node));
            n.put("faceValue", node.faceValue());
            n.put("tokenAmount", node.tokenAmount());
            n.put("unit", node.unit());
            n.put("issuerId", node.issuerId());
            n.put("issuerPublicKey", node.issuerPublicKey());
        });

        ObjectNode children = root.putObject("children");
        tree.childrenMap().forEach((parent, childList) -> {
            ArrayNode arr = children.putArray(parent);
            childList.forEach(arr::add);
        });

        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":\"Failed to render JSON\"}";
        }
    }

    private String formatStatus(VoucherNode node) {
        return node.status() == null ? "unknown" : node.status().name().toLowerCase();
    }
}
