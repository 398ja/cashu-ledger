package xyz.tcheeric.cashu.ledger.trace.publisher.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SqliteOutboxStore}: idempotent enqueue and durability of
 * pending rows across a store reopen (simulating a process restart).
 */
class SqliteOutboxStoreTest {

    private static OutboxRecord row(String opId, long createdMs) {
        return OutboxRecord.pending(opId, "evt-" + opId, "{\"id\":\"evt-" + opId + "\"}", createdMs);
    }

    /** Tests that re-enqueuing the same operation id is a no-op (INSERT OR IGNORE). */
    @Test
    void shouldEnqueueIdempotently() {
        // Arrange
        SqliteOutboxStore store = new SqliteOutboxStore("jdbc:sqlite::memory:");

        // Act
        boolean first = store.enqueue(row("op-1", 1000));
        boolean second = store.enqueue(row("op-1", 2000));

        // Then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(store.pendingCount()).isEqualTo(1);
        store.close();
    }

    /**
     * Tests that pending rows survive a store reopen (the durability guarantee that
     * lets buffered events redeliver after a crash).
     */
    @Test
    void shouldPersistPendingRowsAcrossReopen(@TempDir Path dir) {
        // Arrange: enqueue then close
        String url = "jdbc:sqlite:" + dir.resolve("outbox.db");
        SqliteOutboxStore first = new SqliteOutboxStore(url);
        first.enqueue(row("op-1", 1000));
        first.close();

        // Act: reopen
        SqliteOutboxStore reopened = new SqliteOutboxStore(url);

        // Then: the pending row is still there
        assertThat(reopened.pendingCount()).isEqualTo(1);
        assertThat(reopened.find("op-1")).isPresent();
        reopened.close();
    }

    /** Tests that a delivered row is no longer counted as pending. */
    @Test
    void shouldNotCountDeliveredRowsAsPending() {
        // Arrange
        SqliteOutboxStore store = new SqliteOutboxStore("jdbc:sqlite::memory:");
        store.enqueue(row("op-1", 1000));

        // Act
        store.markDelivered("op-1");

        // Then
        assertThat(store.pendingCount()).isZero();
        store.close();
    }
}
