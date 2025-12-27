package xyz.tcheeric.cashu.ledger.cli.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xyz.tcheeric.cashu.ledger.core.model.ParentContribution;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;
import xyz.tcheeric.cashu.ledger.core.state.StatusChange;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.StringJoiner;

final class ExportFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private final String format;

    ExportFormatter(String format) {
        this.format = format == null ? "json" : format.toLowerCase();
    }

    String format(VoucherNode node, VoucherTree tree, List<StatusChange> history, List<String> historyWarnings) {
        if ("csv".equals(format)) {
            return formatCsv(node, tree, history, historyWarnings);
        }
        return formatJson(node, tree, history, historyWarnings);
    }

    private String formatJson(VoucherNode node, VoucherTree tree, List<StatusChange> history, List<String> historyWarnings) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.set("voucher", toJson(node));

        if (tree != null) {
            ObjectNode treeNode = root.putObject("tree");
            treeNode.put("target", tree.target().voucherId());
            treeNode.put("root", tree.root() != null ? tree.root().voucherId() : null);
            ArrayNode nodes = treeNode.putArray("nodes");
            tree.nodes().values().forEach(n -> nodes.add(toJson(n)));

            ObjectNode children = treeNode.putObject("children");
            tree.childrenMap().forEach((parent, childList) -> {
                ArrayNode arr = children.putArray(parent);
                childList.forEach(arr::add);
            });
        }

        if (history != null && !history.isEmpty()) {
            ArrayNode historyNode = root.putArray("history");
            history.forEach(change -> {
                ObjectNode c = historyNode.addObject();
                c.put("voucherId", change.voucherId());
                c.put("status", change.status().name().toLowerCase());
                c.put("previousStatus", change.previousStatus().name().toLowerCase());
                c.put("stateVersion", change.stateVersion());
                c.put("transitionAt", formatInstant(change.transitionAt()));
                c.put("createdAt", formatInstant(change.createdAt()));
                c.put("relay", change.relay());
                c.put("eventId", change.eventId());
                c.put("transitionActor", change.transitionActor() != null ? change.transitionActor().name().toLowerCase() : "unknown");
            });
        }
        if (historyWarnings != null && !historyWarnings.isEmpty()) {
            ArrayNode warn = root.putArray("historyWarnings");
            historyWarnings.forEach(warn::add);
        }

        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":\"Failed to render JSON\"}";
        }
    }

    private ObjectNode toJson(VoucherNode node) {
        ObjectNode n = OBJECT_MAPPER.createObjectNode();
        n.put("voucherId", node.voucherId());
        n.put("status", node.status() != null ? node.status().name().toLowerCase() : "unknown");
        n.put("issuerId", node.issuerId());
        n.put("issuerPublicKey", node.issuerPublicKey());
        n.put("faceValue", node.faceValue());
        n.put("originalFaceValue", node.originalFaceValue());
        n.put("tokenAmount", node.tokenAmount());
        n.put("originalTokenAmount", node.originalTokenAmount());
        n.put("unit", node.unit());
        n.put("decimals", node.faceDecimals());
        n.put("backingStrategy", node.backingStrategy().name());
        n.put("issuanceRatio", node.issuanceRatio());
        n.put("issuedAt", formatInstant(node.issuedAt()));
        n.put("expiresAt", formatInstant(node.expiresAt()));

        var parents = n.putArray("parents");
        if (node.parentContributions() != null) {
            for (ParentContribution parent : node.parentContributions()) {
                ObjectNode p = parents.addObject();
                p.put("voucherId", parent.parentVoucherId());
                p.put("contributedTokenAmount", parent.contributedTokenAmount());
                p.put("contributedFaceValue", parent.contributedFaceValue());
            }
        }
        return n;
    }

    private String formatCsv(VoucherNode node, VoucherTree tree, List<StatusChange> history, List<String> historyWarnings) {
        StringBuilder sb = new StringBuilder();
        sb.append("voucherId,status,issuerId,faceValue,tokenAmount,parents,issuedAt,relay").append(System.lineSeparator());
        appendCsvRow(sb, node);
        if (tree != null) {
            tree.nodes().values().forEach(n -> {
                if (!n.voucherId().equals(node.voucherId())) {
                    appendCsvRow(sb, n);
                }
            });
        }
        if (history != null && !history.isEmpty()) {
            sb.append(System.lineSeparator()).append("voucherId,status,previousStatus,stateVersion,transitionAt,createdAt,relay,eventId,transitionActor").append(System.lineSeparator());
            history.forEach(change -> sb.append(String.join(",",
                    safe(change.voucherId()),
                    safe(change.status().name().toLowerCase()),
                    safe(change.previousStatus().name().toLowerCase()),
                    Long.toString(change.stateVersion()),
                    safe(formatInstant(change.transitionAt())),
                    safe(formatInstant(change.createdAt())),
                    safe(change.relay()),
                    safe(change.eventId()),
                    safe(change.transitionActor() != null ? change.transitionActor().name().toLowerCase() : "unknown")
            )).append(System.lineSeparator()));
        }
        if (historyWarnings != null && !historyWarnings.isEmpty()) {
            sb.append(System.lineSeparator()).append("historyWarnings").append(System.lineSeparator());
            historyWarnings.forEach(msg -> sb.append(safe(msg)).append(System.lineSeparator()));
        }
        return sb.toString();
    }

    private void appendCsvRow(StringBuilder sb, VoucherNode node) {
        StringJoiner parents = new StringJoiner("|");
        if (node.parentContributions() != null) {
            node.parentContributions().forEach(p -> parents.add(p.parentVoucherId()));
        }
        sb.append(String.join(",",
                safe(node.voucherId()),
                safe(node.status() != null ? node.status().name().toLowerCase() : "unknown"),
                safe(node.issuerId()),
                Long.toString(node.faceValue()),
                Long.toString(node.tokenAmount()),
                safe(parents.toString()),
                safe(formatInstant(node.issuedAt())),
                safe(node.eventMetadata() != null ? node.eventMetadata().relay() : "")
        )).append(System.lineSeparator());
    }

    private String safe(String value) {
        return value == null ? "" : value.replace(",", ";");
    }

    private String formatInstant(java.time.Instant instant) {
        return instant == null ? "" : ISO.format(instant);
    }
}
