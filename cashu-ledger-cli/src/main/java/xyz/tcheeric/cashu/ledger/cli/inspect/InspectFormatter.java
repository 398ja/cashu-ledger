package xyz.tcheeric.cashu.ledger.cli.inspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xyz.tcheeric.cashu.ledger.core.model.ParentContribution;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;

import java.time.format.DateTimeFormatter;
import java.util.List;

final class InspectFormatter {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OutputFormat format;

    InspectFormatter(String format) {
        this.format = OutputFormat.from(format);
    }

    String format(VoucherNode node) {
        return switch (format) {
            case JSON -> formatJson(node);
            case TREE -> formatTree(node);
            case TEXT -> formatText(node);
        };
    }

    private String formatText(VoucherNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("Voucher: ").append(node.voucherId()).append(System.lineSeparator());
        sb.append("Status:           ").append(node.status()).append(System.lineSeparator());
        sb.append("Issuer ID:        ").append(orUnknown(node.issuerId())).append(System.lineSeparator());
        sb.append("Issuer PubKey:    ").append(orUnknown(node.issuerPublicKey())).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        sb.append("Value").append(System.lineSeparator());
        sb.append("  Face Value:     ").append(node.faceValue()).append(" (orig: ").append(node.originalFaceValue()).append(")")
                .append(System.lineSeparator());
        sb.append("  Token Amount:   ").append(node.tokenAmount()).append(" (orig: ").append(node.originalTokenAmount()).append(")")
                .append(System.lineSeparator());
        sb.append("  Issuance Ratio: ").append(node.issuanceRatio()).append(System.lineSeparator());
        sb.append("  Unit:           ").append(orUnknown(node.unit())).append(" (decimals: ").append(node.faceDecimals()).append(")")
                .append(System.lineSeparator());
        sb.append("  Backing:        ").append(node.backingStrategy()).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        sb.append("Lifecycle").append(System.lineSeparator());
        sb.append("  Issued At:      ").append(formatInstant(node.issuedAt())).append(System.lineSeparator());
        sb.append("  Expires At:     ").append(formatInstant(node.expiresAt())).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        sb.append("Parents").append(System.lineSeparator());
        if (node.parentContributions() == null || node.parentContributions().isEmpty()) {
            sb.append("  none (root voucher)").append(System.lineSeparator());
        } else {
            for (ParentContribution parent : node.parentContributions()) {
                sb.append("  - ").append(parent.parentVoucherId())
                        .append(" tokens=").append(parent.contributedTokenAmount())
                        .append(" face=").append(parent.contributedFaceValue())
                        .append(System.lineSeparator());
            }
        }
        sb.append(System.lineSeparator());

        sb.append("Event Metadata").append(System.lineSeparator());
        sb.append("  Event ID:       ").append(node.eventMetadata().eventId()).append(System.lineSeparator());
        sb.append("  Relay:          ").append(orUnknown(node.eventMetadata().relay())).append(System.lineSeparator());
        sb.append("  Created At:     ").append(formatInstant(node.eventMetadata().createdAt())).append(System.lineSeparator());

        return sb.toString();
    }

    private String formatJson(VoucherNode node) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("voucherId", node.voucherId());
        root.put("status", node.status());
        root.put("issuerId", node.issuerId());
        root.put("issuerPublicKey", node.issuerPublicKey());

        ObjectNode value = root.putObject("value");
        value.put("faceValue", node.faceValue());
        value.put("originalFaceValue", node.originalFaceValue());
        value.put("tokenAmount", node.tokenAmount());
        value.put("originalTokenAmount", node.originalTokenAmount());
        value.put("issuanceRatio", node.issuanceRatio());
        value.put("unit", node.unit());
        value.put("decimals", node.faceDecimals());
        value.put("backingStrategy", node.backingStrategy().name());

        ObjectNode lifecycle = root.putObject("lifecycle");
        lifecycle.put("issuedAt", formatInstant(node.issuedAt()));
        lifecycle.put("expiresAt", formatInstant(node.expiresAt()));

        ObjectNode event = root.putObject("event");
        event.put("eventId", node.eventMetadata().eventId());
        event.put("relay", node.eventMetadata().relay());
        event.put("createdAt", formatInstant(node.eventMetadata().createdAt()));
        event.put("kind", node.eventMetadata().kind());

        var parents = root.putArray("parents");
        if (node.parentContributions() != null) {
            for (ParentContribution parent : node.parentContributions()) {
                ObjectNode p = parents.addObject();
                p.put("voucherId", parent.parentVoucherId());
                p.put("contributedTokenAmount", parent.contributedTokenAmount());
                p.put("contributedFaceValue", parent.contributedFaceValue());
            }
        }

        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":\"Failed to render JSON\"}";
        }
    }

    private String formatTree(VoucherNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("Voucher Tree").append(System.lineSeparator());
        sb.append("◉ ").append(node.voucherId())
                .append(" [").append(node.faceValue()).append(", ").append(node.tokenAmount()).append("] ")
                .append(node.status()).append(System.lineSeparator());
        if (node.parentContributions() == null || node.parentContributions().isEmpty()) {
            sb.append("└── (root)").append(System.lineSeparator());
        } else {
            sb.append("└── parents").append(System.lineSeparator());
            List<ParentContribution> parents = node.parentContributions();
            for (int i = 0; i < parents.size(); i++) {
                ParentContribution parent = parents.get(i);
                boolean last = i == parents.size() - 1;
                sb.append(last ? "    └── " : "    ├── ")
                        .append(parent.parentVoucherId())
                        .append(" [tokens=").append(parent.contributedTokenAmount())
                        .append(", face=").append(parent.contributedFaceValue())
                        .append("]").append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    private String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String formatInstant(java.time.Instant instant) {
        return instant == null ? "unknown" : ISO.format(instant);
    }
}
