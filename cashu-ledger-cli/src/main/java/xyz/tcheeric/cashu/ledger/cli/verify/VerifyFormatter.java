package xyz.tcheeric.cashu.ledger.cli.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xyz.tcheeric.cashu.ledger.cli.inspect.OutputFormat;
import xyz.tcheeric.cashu.ledger.core.state.VerificationReport;

final class VerifyFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OutputFormat format;

    VerifyFormatter(String format) {
        this.format = OutputFormat.from(format);
    }

    String format(VerificationReport report) {
        return switch (format) {
            case JSON -> formatJson(report);
            case CSV -> formatCsv(report);
            case TREE, TEXT -> formatText(report);
        };
    }

    private String formatText(VerificationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Verification for ").append(report.voucherId()).append(System.lineSeparator());
        sb.append("Found:              ").append(report.found()).append(System.lineSeparator());
        sb.append("Signature Valid:    ").append(report.signatureValid()).append(System.lineSeparator());
        sb.append("Value Conserved:    ").append(report.valueConserved()).append(System.lineSeparator());
        sb.append("Hierarchy Complete: ").append(report.hierarchyComplete()).append(System.lineSeparator());
        sb.append("State Audit:        ").append(report.stateTransitionsValid()).append(System.lineSeparator());
        if (report.message() != null && !report.message().isBlank()) {
            sb.append("Message:            ").append(report.message()).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private String formatJson(VerificationReport report) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("voucherId", report.voucherId());
        root.put("found", report.found());
        root.put("signatureValid", report.signatureValid());
        root.put("valueConserved", report.valueConserved());
        root.put("hierarchyComplete", report.hierarchyComplete());
        root.put("stateTransitionsValid", report.stateTransitionsValid());
        root.put("message", report.message());
        var warnings = root.putArray("auditIssues");
        report.auditIssues().forEach(warnings::add);
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":\"Failed to render JSON\"}";
        }
    }

    private String formatCsv(VerificationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("voucherId,found,signatureValid,valueConserved,hierarchyComplete,stateTransitionsValid,message")
                .append(System.lineSeparator());
        sb.append(String.join(",",
                safe(report.voucherId()),
                Boolean.toString(report.found()),
                Boolean.toString(report.signatureValid()),
                Boolean.toString(report.valueConserved()),
                Boolean.toString(report.hierarchyComplete()),
                Boolean.toString(report.stateTransitionsValid()),
                safe(report.message())
        )).append(System.lineSeparator());
        if (!report.auditIssues().isEmpty()) {
            sb.append(System.lineSeparator()).append("auditIssues").append(System.lineSeparator());
            report.auditIssues().forEach(issue -> sb.append(safe(issue)).append(System.lineSeparator()));
        }
        return sb.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value.replace(",", ";");
    }
}
