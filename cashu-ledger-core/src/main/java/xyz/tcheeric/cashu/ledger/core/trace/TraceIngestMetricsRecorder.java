package xyz.tcheeric.cashu.ledger.core.trace;

import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;

/**
 * Records ingest outcomes for observability without coupling the ingest path to a metrics
 * library (design §8, T037): events stored by kind, rejections by reason, duplicates,
 * conflicts, and the ingest-to-visible lag. The web layer binds this to Micrometer; the
 * default is a no-op.
 */
public interface TraceIngestMetricsRecorder {

    TraceIngestMetricsRecorder NOOP = new TraceIngestMetricsRecorder() { };

    /** A new event was stored. {@code lagMillis} is now minus the event's created_at. */
    default void recordStored(OperationKind kind, long lagMillis) {
    }

    /** An event was rejected with the given error code. */
    default void recordRejected(String reasonCode) {
    }

    /** A duplicate of an already-stored event was received. */
    default void recordDuplicate() {
    }

    /** An operation-id conflict was detected. */
    default void recordConflict() {
    }
}
