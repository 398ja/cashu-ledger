package xyz.tcheeric.cashu.ledger.trace.publisher.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryOutboxStore} covering the {@link OutboxStore}
 * contract: idempotent enqueue, retry scheduling, ordering, and overflow drop.
 */
class InMemoryOutboxStoreTest {

    private final OutboxStore store = new InMemoryOutboxStore();

    private static OutboxRecord row(String opId, long createdMs) {
        return OutboxRecord.pending(opId, "evt-" + opId, "{\"id\":\"evt-" + opId + "\"}", createdMs);
    }

    /** Tests that enqueuing the same operation id twice stores only one row. */
    @Test
    void shouldEnqueueIdempotentlyByOperationId() {
        // Arrange / Act
        boolean first = store.enqueue(row("op-1", 1000));
        boolean second = store.enqueue(row("op-1", 2000));

        // Then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(store.pendingCount()).isEqualTo(1);
    }

    /** Tests that claimBatch returns only rows due now, oldest first, up to the limit. */
    @Test
    void shouldClaimDueRowsOldestFirstWithinLimit() {
        // Arrange: three rows, one scheduled in the future
        store.enqueue(row("op-a", 1000));
        store.enqueue(row("op-b", 500));
        OutboxRecord future = new OutboxRecord("op-c", "evt-c", "{}", 100, 0, 9999, OutboxStatus.PENDING);
        store.enqueue(future);

        // Act
        List<OutboxRecord> batch = store.claimBatch(10, 2000);

        // Then: op-c not due; op-b before op-a by created time
        assertThat(batch).extracting(OutboxRecord::operationId).containsExactly("op-b", "op-a");
    }

    /** Tests that marking a row delivered removes it from the pending set. */
    @Test
    void shouldRemoveDeliveredRowsFromPending() {
        // Arrange
        store.enqueue(row("op-1", 1000));

        // Act
        store.markDelivered("op-1");

        // Then
        assertThat(store.pendingCount()).isZero();
    }

    /** Tests that recording a failure increments attempts and reschedules the row. */
    @Test
    void shouldRescheduleOnFailure() {
        // Arrange
        store.enqueue(row("op-1", 1000));

        // Act
        store.recordFailure("op-1", 5000);

        // Then
        OutboxRecord updated = store.find("op-1").orElseThrow();
        assertThat(updated.attempts()).isEqualTo(1);
        assertThat(updated.nextAttemptAtEpochMs()).isEqualTo(5000);
        assertThat(store.claimBatch(10, 4999)).isEmpty();
        assertThat(store.claimBatch(10, 5000)).hasSize(1);
    }

    /** Tests that deleteOldestPending drops the earliest row (overflow DROP_OLDEST). */
    @Test
    void shouldDeleteOldestPendingRow() {
        // Arrange
        store.enqueue(row("op-old", 100));
        store.enqueue(row("op-new", 200));

        // Act
        var dropped = store.deleteOldestPending();

        // Then
        assertThat(dropped).contains("op-old");
        assertThat(store.find("op-old")).isEmpty();
        assertThat(store.find("op-new")).isPresent();
    }
}
