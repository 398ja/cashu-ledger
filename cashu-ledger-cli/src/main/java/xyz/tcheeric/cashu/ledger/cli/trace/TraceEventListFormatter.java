package xyz.tcheeric.cashu.ledger.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Formats an event-page response ({@code {events:[…], nextCursor}}) for the CLI. In
 * {@code json} mode the raw payload is pretty-printed; otherwise each event is summarised on
 * one line (kind, mint, transition time, activity, id) followed by any continuation cursor.
 */
final class TraceEventListFormatter {

    private TraceEventListFormatter() {
    }

    static String format(JsonNode page, String output) {
        if ("json".equalsIgnoreCase(output)) {
            return page.toPrettyString();
        }
        JsonNode events = page.path("events");
        if (!events.isArray() || events.isEmpty()) {
            return "No events.";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode event : events) {
            out.append(String.format("%-22s %-28s %-26s %-9s %s%n",
                    event.path("kind").asText("?"),
                    event.path("mintUrl").asText("?"),
                    event.path("transitionAt").asText("?"),
                    event.path("activity").asText("?"),
                    event.path("eventId").asText("?")));
        }
        if (page.hasNonNull("nextCursor")) {
            out.append("-- more: --cursor ").append(page.get("nextCursor").asText()).append('\n');
        }
        return out.toString().stripTrailing();
    }
}
