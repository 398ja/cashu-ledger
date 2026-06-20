package xyz.tcheeric.cashu.ledger.trace.publisher;

import java.util.List;
import java.util.function.LongSupplier;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxRecord;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxStore;

/**
 * Reconciles the three views of what a producer emitted (design FR-036 / SC-001): the count of
 * domain operations that should have been traced, the rows still pending in the outbox, and the
 * events the ledger has actually ingested (read from its {@code /stats}). Surfaces an alert when
 * operations are missing from the ledger beyond what the outbox can still deliver, plus the
 * stuck-delivery view, so an operator can tell "still in flight" from "lost".
 */
public final class Reconciler {

    /** Default failed-attempt threshold above which a pending row counts as stuck. */
    public static final int DEFAULT_STUCK_THRESHOLD = 5;
    private static final int STUCK_LIMIT = 100;

    private final OutboxStore outbox;
    private final LongSupplier ledgerIndexedCount;
    private final int stuckThreshold;

    public Reconciler(OutboxStore outbox, LongSupplier ledgerIndexedCount) {
        this(outbox, ledgerIndexedCount, DEFAULT_STUCK_THRESHOLD);
    }

    public Reconciler(OutboxStore outbox, LongSupplier ledgerIndexedCount, int stuckThreshold) {
        this.outbox = outbox;
        this.ledgerIndexedCount = ledgerIndexedCount;
        this.stuckThreshold = stuckThreshold;
    }

    /** Reconciles against the number of domain operations that should have been traced. */
    public ReconciliationReport reconcile(long domainOperationCount) {
        long pending = outbox.pendingCount();
        long ledgerIndexed = ledgerIndexedCount.getAsLong();
        long missing = Math.max(0, domainOperationCount - ledgerIndexed);
        long unaccounted = Math.max(0, missing - pending);
        List<OutboxRecord> stuck = outbox.stuck(stuckThreshold, STUCK_LIMIT);
        boolean alert = unaccounted > 0 || !stuck.isEmpty();
        return new ReconciliationReport(domainOperationCount, pending, ledgerIndexed,
                missing, unaccounted, stuck, alert);
    }
}
