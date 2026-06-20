package xyz.tcheeric.cashu.ledger.web.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The trace ledger's index health and schema posture (design §5.5, FR-036). Ingest
 * reconciliation counters (accepted, duplicates, rejected-by-reason, per-producer lag)
 * are added once the ingest pipeline is wired into the serving context.
 *
 * @param available           whether the index is queryable
 * @param rebuilding          whether a sidecar rebuild is in progress
 * @param indexedEventCount   number of events currently indexed
 * @param latestTransitionAt  newest indexed transition time, ISO-8601, or null
 * @param currentSchemaVersion the schema version this ledger emits
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatsView(
        boolean available,
        boolean rebuilding,
        long indexedEventCount,
        String latestTransitionAt,
        int currentSchemaVersion) {
}
