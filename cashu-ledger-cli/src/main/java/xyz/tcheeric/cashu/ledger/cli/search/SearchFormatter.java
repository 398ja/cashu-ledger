package xyz.tcheeric.cashu.ledger.cli.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xyz.tcheeric.cashu.ledger.cli.inspect.OutputFormat;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;

import java.time.format.DateTimeFormatter;
import java.util.List;

final class SearchFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private final OutputFormat format;

    SearchFormatter(String format) {
        this.format = OutputFormat.from(format);
    }

    String format(List<VoucherNode> vouchers) {
        return switch (format) {
            case JSON -> formatJson(vouchers);
            case CSV -> formatCsv(vouchers);
            case TREE, TEXT -> formatText(vouchers);
        };
    }

    private String formatText(List<VoucherNode> vouchers) {
        StringBuilder sb = new StringBuilder();
        sb.append("Search Results (").append(vouchers.size()).append(")").append(System.lineSeparator());
        sb.append("================================").append(System.lineSeparator());
        vouchers.forEach(node -> {
            sb.append(node.voucherId()).append(" | ")
                    .append(node.status() != null ? node.status().name().toLowerCase() : "unknown")
                    .append(" | issuer=").append(node.issuerId() == null ? "unknown" : node.issuerId())
                    .append(" | face=").append(node.faceValue())
                    .append(" | token=").append(node.tokenAmount())
                    .append(" | issued=").append(formatInstant(node.issuedAt()))
                    .append(System.lineSeparator());
        });
        return sb.toString();
    }

    private String formatJson(List<VoucherNode> vouchers) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        vouchers.forEach(node -> {
            ObjectNode n = array.addObject();
            n.put("voucherId", node.voucherId());
            n.put("status", node.status() != null ? node.status().name().toLowerCase() : "unknown");
            n.put("issuerId", node.issuerId());
            n.put("faceValue", node.faceValue());
            n.put("tokenAmount", node.tokenAmount());
            n.put("unit", node.unit());
            n.put("issuedAt", formatInstant(node.issuedAt()));
            n.put("relay", node.eventMetadata() != null ? node.eventMetadata().relay() : null);
        });
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(array);
        } catch (Exception e) {
            return "{\"error\":\"Failed to render JSON\"}";
        }
    }

    private String formatCsv(List<VoucherNode> vouchers) {
        StringBuilder sb = new StringBuilder();
        sb.append("voucherId,status,issuerId,faceValue,tokenAmount,unit,issuedAt,relay").append(System.lineSeparator());
        vouchers.forEach(node -> sb.append(String.join(",",
                        safe(node.voucherId()),
                        safe(node.status() != null ? node.status().name().toLowerCase() : "unknown"),
                        safe(node.issuerId()),
                        Long.toString(node.faceValue()),
                        Long.toString(node.tokenAmount()),
                        safe(node.unit()),
                        safe(formatInstant(node.issuedAt())),
                        safe(node.eventMetadata() != null ? node.eventMetadata().relay() : "")
                )).append(System.lineSeparator()));
        return sb.toString();
    }

    private String formatInstant(java.time.Instant instant) {
        return instant == null ? "unknown" : ISO.format(instant);
    }

    private String safe(String value) {
        return value == null ? "" : value.replace(",", ";");
    }
}
