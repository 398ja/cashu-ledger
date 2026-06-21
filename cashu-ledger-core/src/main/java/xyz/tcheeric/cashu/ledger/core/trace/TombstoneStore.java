package xyz.tcheeric.cashu.ledger.core.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual-prune hook over the indexed store (design §5.11). Pruning removes an event's raw
 * signed payload while the sidecar retains its index and proof_ref rows plus a tombstone, so
 * the hop stays traversable as a pruned-event placeholder and surfaces in walks via
 * {@code prunedCount}. The full age/sub-DAG retention engine (T072) builds on this primitive.
 */
public final class TombstoneStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(TombstoneStore.class);

    private final IndexedTraceEventStore store;

    public TombstoneStore(IndexedTraceEventStore store) {
        this.store = store;
    }

    /** Prunes one event, returning {@code true} if a raw payload was removed. */
    public boolean prune(String eventId) {
        boolean removed = store.tombstone(eventId);
        LOGGER.info("trace_event_pruned event_id={} raw_removed={}", eventId, removed);
        return removed;
    }
}
