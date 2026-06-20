package xyz.tcheeric.cashu.ledger.trace.publisher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxStorageException;

/**
 * Durable {@link OperationIdRegistry} backed by SQLite. The mapping is persisted
 * so that a UUIDv7 generated for a domain operation is reused across retries,
 * crash recovery, and backfills (design FR-19a).
 */
public final class SqliteOperationIdRegistry implements OperationIdRegistry, AutoCloseable {

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    public SqliteOperationIdRegistry(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS operation_id_registry (
                          operation_type TEXT NOT NULL,
                          primary_key    TEXT NOT NULL,
                          operation_id   TEXT NOT NULL,
                          PRIMARY KEY (operation_type, primary_key)
                        )""");
            }
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to open operation-id registry at " + jdbcUrl, e);
        }
    }

    @Override
    public String resolve(String operationType, String primaryKey) {
        lock.lock();
        try {
            Optional<String> existing = findUnlocked(operationType, primaryKey);
            if (existing.isPresent()) {
                return existing.get();
            }
            String generated = OperationIds.uuidV7();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO operation_id_registry "
                            + "(operation_type, primary_key, operation_id) VALUES (?, ?, ?)")) {
                ps.setString(1, operationType);
                ps.setString(2, primaryKey);
                ps.setString(3, generated);
                ps.executeUpdate();
            }
            // Re-read to win any race deterministically (the first inserted id stands).
            return findUnlocked(operationType, primaryKey).orElse(generated);
        } catch (SQLException e) {
            throw new OutboxStorageException(
                    "Failed to resolve operation id for " + operationType + "/" + primaryKey, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> find(String operationType, String primaryKey) {
        lock.lock();
        try {
            return findUnlocked(operationType, primaryKey);
        } catch (SQLException e) {
            throw new OutboxStorageException(
                    "Failed to look up operation id for " + operationType + "/" + primaryKey, e);
        } finally {
            lock.unlock();
        }
    }

    private Optional<String> findUnlocked(String operationType, String primaryKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT operation_id FROM operation_id_registry "
                        + "WHERE operation_type = ? AND primary_key = ?")) {
            ps.setString(1, operationType);
            ps.setString(2, primaryKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            connection.close();
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to close operation-id registry", e);
        } finally {
            lock.unlock();
        }
    }
}
