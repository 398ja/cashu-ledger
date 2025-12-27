package xyz.tcheeric.cashu.ledger.cli.unclaimed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xyz.tcheeric.cashu.ledger.cli.inspect.OutputFormat;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.state.ReclaimOutcome;
import xyz.tcheeric.cashu.ledger.core.state.UnclaimedStatusResult;

import java.time.format.DateTimeFormatter;
import java.util.List;

final class UnclaimedFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private final OutputFormat format;

    UnclaimedFormatter(String format) {
        this.format = OutputFormat.from(format);
    }

    String formatList(List<VoucherNode> vouchers) {
        return switch (format) {
            case JSON -> formatListJson(vouchers);
            case CSV -> formatListCsv(vouchers);
            case TREE, TEXT -> formatListText(vouchers);
        };
    }

    String formatCheck(UnclaimedStatusResult result) {
        return switch (format) {
            case JSON -> formatCheckJson(result);
            case CSV, TREE, TEXT -> formatCheckText(result);
        };
    }

    String formatReclaim(ReclaimOutcome outcome) {
        return switch (format) {
            case JSON -> formatReclaimJson(outcome);
            case CSV, TREE, TEXT -> formatReclaimText(outcome);
        };
    }

    private String formatListText(List<VoucherNode> vouchers) {
        StringBuilder sb = new StringBuilder();
        sb.append("Unclaimed Vouchers (").append(vouchers.size()).append(")").append(System.lineSeparator());
        sb.append("================================").append(System.lineSeparator());
        vouchers.forEach(node -> sb.append(node.voucherId())
                .append(" | face=").append(node.faceValue())
                .append(" | token=").append(node.tokenAmount())
                .append(" | issuer=").append(node.issuerId() == null ? "unknown" : node.issuerId())
                .append(" | issued=").append(formatInstant(node.issuedAt()))
                .append(System.lineSeparator()));
        return sb.toString();
    }

    private String formatListJson(List<VoucherNode> vouchers) {
        ArrayNode arr = OBJECT_MAPPER.createArrayNode();
        vouchers.forEach(node -> {
            ObjectNode n = arr.addObject();
            n.put("voucherId", node.voucherId());
            n.put("faceValue", node.faceValue());
            n.put("tokenAmount", node.tokenAmount());
            n.put("issuerId", node.issuerId());
            n.put("issuedAt", formatInstant(node.issuedAt()));
            n.put("relay", node.eventMetadata() != null ? node.eventMetadata().relay() : null);
        });
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
        } catch (Exception e) {
            return "{\"error\":\"Failed to render JSON\"}";
        }
    }

    private String formatListCsv(List<VoucherNode> vouchers) {
        StringBuilder sb = new StringBuilder();
        sb.append("voucherId,faceValue,tokenAmount,issuerId,issuedAt,relay").append(System.lineSeparator());
        vouchers.forEach(node -> sb.append(String.join(",",
                        safe(node.voucherId()),
                        Long.toString(node.faceValue()),
                        Long.toString(node.tokenAmount()),
                        safe(node.issuerId()),
                        safe(formatInstant(node.issuedAt())),
                        safe(node.eventMetadata() != null ? node.eventMetadata().relay() : "")
                )).append(System.lineSeparator()));
        return sb.toString();
    }

    private String formatCheckText(UnclaimedStatusResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Proof Status for ").append(result.voucherId()).append(System.lineSeparator());
        sb.append("Reclaimable: ").append(result.reclaimable()).append(System.lineSeparator());
        sb.append("Details: ").append(result.message());
        return sb.toString();
    }

    private String formatCheckJson(UnclaimedStatusResult result) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("voucherId", result.voucherId());
        node.put("reclaimable", result.reclaimable());
        node.put("message", result.message());
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"Failed to render JSON\"}";
        }
    }

    private String formatReclaimText(ReclaimOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("Reclaim ").append(outcome.voucherId()).append(System.lineSeparator());
        sb.append("Success: ").append(outcome.success()).append(System.lineSeparator());
        sb.append("New Voucher: ").append(outcome.newVoucherId() == null ? "n/a" : outcome.newVoucherId()).append(System.lineSeparator());
        sb.append("Details: ").append(outcome.message());
        return sb.toString();
    }

    private String formatReclaimJson(ReclaimOutcome outcome) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("voucherId", outcome.voucherId());
        node.put("success", outcome.success());
        node.put("newVoucherId", outcome.newVoucherId());
        node.put("message", outcome.message());
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"Failed to render JSON\"}";
        }
    }

    private String formatInstant(java.time.Instant instant) {
        return instant == null ? "unknown" : ISO.format(instant);
    }

    private String safe(String value) {
        return value == null ? "" : value.replace(",", ";");
    }
}
