package xyz.tcheeric.cashu.ledger.trace.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the operation-id registries: a domain operation always resolves
 * to the same id (idempotency), and the SQLite registry persists ids across reopen.
 */
class OperationIdRegistryTest {

    /** Tests that the in-memory registry returns the same id for repeated resolves. */
    @Test
    void shouldReturnStableIdForSameOperation() {
        // Arrange
        OperationIdRegistry registry = new InMemoryOperationIdRegistry();

        // Act
        String first = registry.resolve("mint", "pk-1");
        String second = registry.resolve("mint", "pk-1");

        // Then
        assertThat(first).isEqualTo(second);
    }

    /** Tests that distinct operations get distinct ids. */
    @Test
    void shouldReturnDistinctIdsForDistinctOperations() {
        // Arrange
        OperationIdRegistry registry = new InMemoryOperationIdRegistry();

        // Act
        String mint = registry.resolve("mint", "pk-1");
        String swap = registry.resolve("swap", "pk-1");

        // Then
        assertThat(mint).isNotEqualTo(swap);
    }

    /**
     * Tests that the SQLite registry reuses a persisted id after a reopen — the
     * property that makes idempotency survive a crash.
     */
    @Test
    void shouldPersistIdAcrossReopen(@TempDir Path dir) {
        // Arrange
        String url = "jdbc:sqlite:" + dir.resolve("opids.db");
        String original;
        try (SqliteOperationIdRegistry first = new SqliteOperationIdRegistry(url)) {
            original = first.resolve("mint", "pk-1");
        }

        // Act
        try (SqliteOperationIdRegistry reopened = new SqliteOperationIdRegistry(url)) {
            String afterRestart = reopened.resolve("mint", "pk-1");

            // Then
            assertThat(afterRestart).isEqualTo(original);
        }
    }
}
