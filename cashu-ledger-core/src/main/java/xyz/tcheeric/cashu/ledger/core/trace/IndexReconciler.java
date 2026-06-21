package xyz.tcheeric.cashu.ledger.core.trace;

import xyz.tcheeric.cashu.ledger.trace.core.IndexStatus;

/**
 * Assesses the sidecar index's serving health (design §5.4, FR — index availability/lag). The
 * read path consults this to fail fast with {@code 503 INDEX_UNAVAILABLE} (sidecar missing or
 * rebuilding) or {@code 503 INDEX_LAGGED} (the sidecar is behind the nostrdb system of record)
 * rather than serve sidecar-derived results from an incomplete index. The backlog also feeds
 * {@code cashu_trace_index_pending_events}.
 *
 * <p>The ledger projects the sidecar synchronously, so the backlog is positive only transiently —
 * at startup before the rebuild completes, or after a rare sidecar write failure that the
 * reconciler retries.</p>
 */
public final class IndexReconciler {

    /** Index serving status. */
    public enum Status { HEALTHY, LAGGED, REBUILDING, UNAVAILABLE }

    /** A health snapshot: the status and the number of events not yet in the sidecar. */
    public record Health(Status status, long pendingEvents) {
        /** Whether sidecar-derived queries should be served (only HEALTHY). */
        public boolean isServable() {
            return status == Status.HEALTHY;
        }
    }

    private final IndexedTraceEventStore store;

    public IndexReconciler(IndexedTraceEventStore store) {
        this.store = store;
    }

    /** Computes the current index health. */
    public Health check() {
        IndexStatus status = store.getIndexStatus();
        if (!status.available()) {
            return new Health(Status.UNAVAILABLE, 0);
        }
        if (status.rebuilding()) {
            return new Health(Status.REBUILDING, 0);
        }
        long pending = store.pendingIndexCount();
        return new Health(pending > 0 ? Status.LAGGED : Status.HEALTHY, pending);
    }

    /** Events in the system of record not yet projected into the sidecar (0 when caught up). */
    public long pendingEvents() {
        return store.pendingIndexCount();
    }
}
