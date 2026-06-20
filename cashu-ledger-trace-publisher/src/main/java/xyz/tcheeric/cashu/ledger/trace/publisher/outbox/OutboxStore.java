package xyz.tcheeric.cashu.ledger.trace.publisher.outbox;

import java.util.List;
import java.util.Optional;

/**
 * Durable, idempotent store of outbound trace events. Implementations must survive
 * process restarts (the SQLite implementation is the default) and must enqueue
 * idempotently keyed on {@code operationId} (design FR-15/FR-18).
 */
public interface OutboxStore {

    /**
     * Enqueues a row, idempotently. Returns {@code true} if newly stored,
     * {@code false} if a row with the same {@code operationId} already existed.
     */
    boolean enqueue(OutboxRecord record);

    /** Count of rows still pending delivery. */
    long pendingCount();

    /**
     * Returns up to {@code max} pending rows whose {@code nextAttemptAt} is at or
     * before {@code nowEpochMs}, oldest first.
     */
    List<OutboxRecord> claimBatch(int max, long nowEpochMs);

    /** Marks the row delivered (removes it from the pending set). */
    void markDelivered(String operationId);

    /** Records a failed attempt and schedules the next retry. */
    void recordFailure(String operationId, long nextAttemptAtEpochMs);

    Optional<OutboxRecord> find(String operationId);

    /**
     * Returns up to {@code limit} pending rows that have failed at least {@code minAttempts}
     * times — the "stuck" delivery view for reconciliation, most-attempted first.
     */
    List<OutboxRecord> stuck(int minAttempts, int limit);

    /**
     * Deletes the oldest pending row (for {@link xyz.tcheeric.cashu.ledger.trace.publisher.OverflowPolicy#DROP_OLDEST_AND_ALERT}).
     * Returns the dropped {@code operationId}, or empty if none was pending.
     */
    Optional<String> deleteOldestPending();

    /** Releases any underlying resources (connections, files). */
    default void close() {
    }
}
