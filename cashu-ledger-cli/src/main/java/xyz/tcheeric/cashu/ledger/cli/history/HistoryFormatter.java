package xyz.tcheeric.cashu.ledger.cli.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xyz.tcheeric.cashu.ledger.cli.inspect.OutputFormat;
import xyz.tcheeric.cashu.ledger.core.state.StatusChange;
import xyz.tcheeric.cashu.ledger.core.state.HistoryResult;

import java.time.format.DateTimeFormatter;
import java.util.List;

final class HistoryFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private final OutputFormat format;

    HistoryFormatter(String format) {
        this.format = OutputFormat.from(format);
    }

    String format(String voucherId, HistoryResult result) {
        return switch (format) {
            case JSON -> formatJson(result);
            case CSV -> formatCsv(result);
            case TREE, TEXT -> formatText(voucherId, result);
        };
    }

    private String formatText(String voucherId, HistoryResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Status History for ").append(voucherId).append(System.lineSeparator());
        sb.append("================================").append(System.lineSeparator());
        int idx = 1;
        for (StatusChange change : result.events()) {
            sb.append(String.format(" %d  %s  %s  v%s  relay=%s  event=%s",
                    idx++,
                    formatInstant(change.transitionAt()),
                    change.status().name().toLowerCase(),
                    change.stateVersion(),
                    change.relay() == null ? "unknown" : change.relay(),
                    change.eventId() == null ? "unknown" : change.eventId()
            )).append(System.lineSeparator());
        }
        sb.append(System.lineSeparator()).append("Total: ").append(result.events().size()).append(" status changes");
        if (!result.warnings().isEmpty()) {
            sb.append(System.lineSeparator()).append("Warnings:").append(System.lineSeparator());
            result.warnings().forEach(msg -> sb.append(" - ").append(msg).append(System.lineSeparator()));
        }
        return sb.toString();
    }

    private String formatJson(HistoryResult result) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        result.events().forEach(change -> {
            ObjectNode node = array.addObject();
            node.put("voucherId", change.voucherId());
            node.put("status", change.status().name().toLowerCase());
            node.put("previousStatus", change.previousStatus().name().toLowerCase());
            node.put("stateVersion", change.stateVersion());
            node.put("transitionAt", formatInstant(change.transitionAt()));
            node.put("createdAt", formatInstant(change.createdAt()));
            node.put("relay", change.relay());
            node.put("eventId", change.eventId());
            node.put("transitionActor", change.transitionActor() != null ? change.transitionActor().name().toLowerCase() : "unknown");
        });
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.set("history", array);
        ArrayNode warnings = root.putArray("warnings");
        result.warnings().forEach(warnings::add);
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":\"Failed to render JSON\"}";
        }
    }

    private String formatCsv(HistoryResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("voucherId,status,previousStatus,stateVersion,transitionAt,createdAt,relay,eventId,transitionActor")
                .append(System.lineSeparator());
        result.events().forEach(change -> sb.append(String.join(",",
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
        if (!result.warnings().isEmpty()) {
            sb.append(System.lineSeparator()).append("warnings").append(System.lineSeparator());
            result.warnings().forEach(msg -> sb.append(safe(msg)).append(System.lineSeparator()));
        }
        return sb.toString();
    }

    private String formatInstant(java.time.Instant instant) {
        return instant == null ? "unknown" : ISO.format(instant);
    }

    private String safe(String value) {
        return value == null ? "" : value.replace(",", ";");
    }
}
