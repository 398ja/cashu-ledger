package xyz.tcheeric.cashu.ledger.cli.diff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xyz.tcheeric.cashu.ledger.cli.inspect.OutputFormat;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;

final class DiffFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OutputFormat format;

    DiffFormatter(String format) {
        this.format = OutputFormat.from(format);
    }

    String format(VoucherNode left, VoucherNode right) {
        return switch (format) {
            case JSON -> formatJson(left, right);
            case CSV -> formatCsv(left, right);
            case TREE, TEXT -> formatText(left, right);
        };
    }

    private String formatText(VoucherNode left, VoucherNode right) {
        StringBuilder sb = new StringBuilder();
        sb.append("Comparison: ").append(left.voucherId()).append(" vs ").append(right.voucherId()).append(System.lineSeparator());
        sb.append("Status:    ").append(status(left)).append(" | ").append(status(right)).append(System.lineSeparator());
        sb.append("Issuer:    ").append(left.issuerId()).append(" | ").append(right.issuerId()).append(System.lineSeparator());
        sb.append("Face:      ").append(left.faceValue()).append(" | ").append(right.faceValue()).append(System.lineSeparator());
        sb.append("Token:     ").append(left.tokenAmount()).append(" | ").append(right.tokenAmount()).append(System.lineSeparator());
        sb.append("Unit:      ").append(left.unit()).append(" | ").append(right.unit()).append(System.lineSeparator());
        sb.append("Relationship: ").append(relationship(left, right)).append(System.lineSeparator());
        return sb.toString();
    }

    private String formatJson(VoucherNode left, VoucherNode right) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.set("left", toJson(left));
        root.set("right", toJson(right));
        root.put("relationship", relationship(left, right));
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":\"Failed to render JSON\"}";
        }
    }

    private String formatCsv(VoucherNode left, VoucherNode right) {
        StringBuilder sb = new StringBuilder();
        sb.append("field,left,right").append(System.lineSeparator());
        sb.append("voucherId,").append(left.voucherId()).append(",").append(right.voucherId()).append(System.lineSeparator());
        sb.append("status,").append(status(left)).append(",").append(status(right)).append(System.lineSeparator());
        sb.append("issuer,").append(safe(left.issuerId())).append(",").append(safe(right.issuerId())).append(System.lineSeparator());
        sb.append("face,").append(left.faceValue()).append(",").append(right.faceValue()).append(System.lineSeparator());
        sb.append("token,").append(left.tokenAmount()).append(",").append(right.tokenAmount()).append(System.lineSeparator());
        sb.append("unit,").append(safe(left.unit())).append(",").append(safe(right.unit())).append(System.lineSeparator());
        sb.append("relationship,").append(relationship(left, right)).append(System.lineSeparator());
        return sb.toString();
    }

    private ObjectNode toJson(VoucherNode node) {
        ObjectNode n = OBJECT_MAPPER.createObjectNode();
        n.put("voucherId", node.voucherId());
        n.put("status", status(node));
        n.put("issuerId", node.issuerId());
        n.put("faceValue", node.faceValue());
        n.put("tokenAmount", node.tokenAmount());
        n.put("unit", node.unit());
        return n;
    }

    private String relationship(VoucherNode left, VoucherNode right) {
        if (left.parentContributions() != null && left.parentContributions().stream()
                .anyMatch(p -> p.parentVoucherId().equals(right.voucherId()))) {
            return "RIGHT is parent of LEFT";
        }
        if (right.parentContributions() != null && right.parentContributions().stream()
                .anyMatch(p -> p.parentVoucherId().equals(left.voucherId()))) {
            return "LEFT is parent of RIGHT";
        }
        return left.issuerId() != null && left.issuerId().equals(right.issuerId()) ? "Same issuer" : "Unrelated";
    }

    private String status(VoucherNode node) {
        return node.status() == null ? "unknown" : node.status().name().toLowerCase();
    }

    private String safe(String value) {
        return value == null ? "" : value.replace(",", ";");
    }
}
