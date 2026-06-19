package xyz.tcheeric.cashu.ledger.core.trace;

/**
 * A snapshot of ingest counters for observability (design NFR-8).
 *
 * @param stored     events newly stored
 * @param duplicates events dropped as duplicates
 * @param rejected   events rejected by validation
 * @param conflicts  operation-id conflicts detected
 */
public record TraceIngestMetrics(long stored, long duplicates, long rejected, long conflicts) {
}
