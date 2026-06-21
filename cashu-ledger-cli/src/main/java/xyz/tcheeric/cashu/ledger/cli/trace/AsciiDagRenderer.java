package xyz.tcheeric.cashu.ledger.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a walk response ({@code {nodes, edges, truncated, …}}) as an ASCII tree, one root
 * per source node, descending along edges with their role and amount labels. Cycles and
 * already-printed nodes are marked {@code (seen)} so a DAG with shared descendants stays
 * finite (design §5.5 — {@code trace … --output tree}).
 */
public final class AsciiDagRenderer {

    private record Edge(String to, String role, String amount) {
    }

    public String render(JsonNode walk) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (JsonNode node : walk.path("nodes")) {
            labels.put(node.path("eventId").asText(), node.path("kind").asText("event"));
        }
        Map<String, List<Edge>> children = new LinkedHashMap<>();
        Set<String> targets = new LinkedHashSet<>();
        for (JsonNode edge : walk.path("edges")) {
            String from = edge.path("fromEventId").asText();
            String to = edge.path("toEventId").asText();
            String amount = edge.has("amount") && !edge.get("amount").isNull()
                    ? edge.get("amount").asText() : null;
            children.computeIfAbsent(from, k -> new ArrayList<>())
                    .add(new Edge(to, edge.path("role").asText(""), amount));
            targets.add(to);
        }

        List<String> roots = new ArrayList<>();
        for (String id : labels.keySet()) {
            if (!targets.contains(id)) {
                roots.add(id);
            }
        }
        if (roots.isEmpty()) {
            roots.addAll(labels.keySet()); // fully cyclic: start anywhere
        }

        StringBuilder out = new StringBuilder();
        Set<String> visited = new LinkedHashSet<>();
        for (String root : roots) {
            renderNode(root, "", true, null, labels, children, visited, out);
        }
        if (walk.path("truncated").asBoolean(false)) {
            out.append("… (truncated — raise --depth/limit or narrow the anchor)\n");
        }
        return out.toString();
    }

    private void renderNode(String id, String prefix, boolean isLast, String edgeLabel,
                            Map<String, String> labels, Map<String, List<Edge>> children,
                            Set<String> visited, StringBuilder out) {
        String connector = prefix.isEmpty() ? "" : (isLast ? "└── " : "├── ");
        String label = labels.getOrDefault(id, "event");
        out.append(prefix).append(connector);
        if (edgeLabel != null) {
            out.append('[').append(edgeLabel).append("] ");
        }
        out.append(label).append(' ').append(shortId(id));
        if (visited.contains(id)) {
            out.append(" (seen)\n");
            return;
        }
        out.append('\n');
        visited.add(id);

        List<Edge> kids = children.getOrDefault(id, List.of());
        String childPrefix = prefix + (prefix.isEmpty() ? "" : (isLast ? "    " : "│   "));
        for (int i = 0; i < kids.size(); i++) {
            Edge edge = kids.get(i);
            String edgeText = edge.amount() != null ? edge.role() + " " + edge.amount() : edge.role();
            renderNode(edge.to(), childPrefix, i == kids.size() - 1, edgeText,
                    labels, children, visited, out);
        }
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 12 ? String.valueOf(id) : id.substring(0, 12) + "…";
    }
}
