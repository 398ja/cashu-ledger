package xyz.tcheeric.cashu.ledger.trace.publisher.outbox;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.ledger.trace.publisher.RelayPublishResult;
import xyz.tcheeric.cashu.ledger.trace.publisher.RelayPublisher;

/**
 * Drains the {@link OutboxStore} and delivers rows to the ledger relay set via a
 * {@link RelayPublisher}, with at-least-once semantics and exponential backoff
 * (design FR-16 / §6.3). A single-threaded scheduler preserves ordering; the actual
 * relay I/O is delegated to the {@link RelayPublisher} (which an embedder may run on
 * virtual threads). {@link #drainOnce(long)} is exposed for deterministic testing.
 */
public final class OutboxDispatcher implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);

    /** Backoff parameters (design FR-9): initial 1s, factor 2, cap 5 minutes. */
    public static final long INITIAL_BACKOFF_MS = 1_000L;
    public static final long MAX_BACKOFF_MS = 5 * 60 * 1_000L;

    private final OutboxStore outbox;
    private final RelayPublisher relayPublisher;
    private final int batchSize;
    private final long pollIntervalMs;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock();

    private ScheduledExecutorService scheduler;

    public OutboxDispatcher(OutboxStore outbox, RelayPublisher relayPublisher,
                            int batchSize, long pollIntervalMs, LongSupplier clock) {
        this.outbox = outbox;
        this.relayPublisher = relayPublisher;
        this.batchSize = batchSize;
        this.pollIntervalMs = pollIntervalMs;
        this.clock = clock;
    }

    /** Convenience: batch 64, 250 ms poll, system clock (design §6.3 default cadence). */
    public static OutboxDispatcher create(OutboxStore outbox, RelayPublisher relayPublisher) {
        return new OutboxDispatcher(outbox, relayPublisher, 64, 250L, System::currentTimeMillis);
    }

    /** Starts the background drain loop. Idempotent. */
    public void start() {
        lock.lock();
        try {
            if (scheduler != null) {
                return;
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "trace-outbox-dispatcher");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::drainSafely, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
        } finally {
            lock.unlock();
        }
    }

    private void drainSafely() {
        try {
            drainOnce(clock.getAsLong());
        } catch (RuntimeException e) {
            LOGGER.error("outbox_drain_failed error={}", e.getMessage(), e);
        }
    }

    /**
     * Attempts delivery of one batch of due rows. Delivered rows are marked
     * delivered; failures are rescheduled with exponential backoff. Returns the
     * number of rows delivered.
     */
    public int drainOnce(long nowMs) {
        List<OutboxRecord> batch = outbox.claimBatch(batchSize, nowMs);
        int delivered = 0;
        for (OutboxRecord record : batch) {
            RelayPublishResult result = relayPublisher.publish(record.eventJson(), record.eventId());
            if (result.deliveredToLedgerRelay()) {
                outbox.markDelivered(record.operationId());
                delivered++;
            } else {
                long next = nowMs + backoffMillis(record.attempts());
                outbox.recordFailure(record.operationId(), next);
                LOGGER.warn("outbox_delivery_failed operation_id={} attempts={} next_attempt_ms={} detail={}",
                        record.operationId(), record.attempts() + 1, next, result.detail());
            }
        }
        return delivered;
    }

    /** Exponential backoff: {@code initial * 2^attempts}, capped at the maximum. */
    public static long backoffMillis(int attempts) {
        if (attempts >= 30) {
            return MAX_BACKOFF_MS;
        }
        long candidate = INITIAL_BACKOFF_MS << attempts;
        return Math.min(candidate, MAX_BACKOFF_MS);
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        } finally {
            lock.unlock();
        }
    }
}
