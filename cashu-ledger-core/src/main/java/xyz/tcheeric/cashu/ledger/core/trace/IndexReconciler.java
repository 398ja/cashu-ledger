package xyz.tcheeric.cashu.ledger.core.trace;

import java.time.Clock;
import java.time.Instant;
import xyz.tcheeric.cashu.ledger.trace.core.IndexStatus;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;

/**
 * Assesses the sidecar index's serving health (design §5.4 — index availability/lag, FR). The
 * read path consults this to fail fast with {@code 503 INDEX_UNAVAILABLE} / {@code INDEX_LAGGED}
 * rather than serve from a missing or far-behind index, and the lag feeds
 * {@code cashu_trace_index_lag_seconds}.
 *
 * <p>Startup rebuild against the nostrdb system-of-record and {@code pending_index} retry land
 * with the nostrdb-backed {@code RawEventStore} adapter; this covers the availability/lag signal
 * over the current store.</p>
 */
public final class IndexReconciler {

    /** Index serving status. */
    public enum Status { HEALTHY, LAGGED, REBUILDING, UNAVAILABLE }

    /** A health snapshot: the status and how far behind the newest event the index is. */
    public record Health(Status status, long lagSeconds) {
        public boolean isServable() {
            return status == Status.HEALTHY || status == Status.LAGGED;
        }
    }

    private final TraceEventStore store;
    private final long lagThresholdSeconds;
    private final Clock clock;

    public IndexReconciler(TraceEventStore store, long lagThresholdSeconds, Clock clock) {
        this.store = store;
        this.lagThresholdSeconds = lagThresholdSeconds;
        this.clock = clock;
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
        long lag = status.latestTransitionAt()
                .map(t -> Math.max(0, Instant.now(clock).getEpochSecond() - t.getEpochSecond()))
                .orElse(0L);
        return new Health(lag > lagThresholdSeconds ? Status.LAGGED : Status.HEALTHY, lag);
    }

    /** Seconds the index is behind the newest indexed event (0 when empty/unavailable). */
    public long lagSeconds() {
        return check().lagSeconds();
    }
}
