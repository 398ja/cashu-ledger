package xyz.tcheeric.cashu.ledger.trace.publisher.outbox;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Durable {@link OutboxStore} backed by SQLite. Survives process restarts so that
 * buffered trace events are redelivered after a crash. Enqueue is idempotent via
 * {@code INSERT OR IGNORE} keyed on {@code operation_id} (design §6.3).
 *
 * <p>A single connection is held for the store's lifetime and guarded by a
 * {@link ReentrantLock}; SQLite serialises writers anyway, and the dispatcher is
 * single-threaded.</p>
 */
public final class SqliteOutboxStore implements OutboxStore {

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Opens (creating if needed) an outbox database at {@code jdbcUrl}, e.g.
     * {@code jdbc:sqlite:/var/lib/cashu/outbox.db} or {@code jdbc:sqlite::memory:}.
     */
    public SqliteOutboxStore(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            initSchema();
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to open outbox database at " + jdbcUrl, e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS outbox (
                      operation_id    TEXT PRIMARY KEY,
                      event_id        TEXT NOT NULL,
                      event_json      TEXT NOT NULL,
                      created_at_ms   INTEGER NOT NULL,
                      attempts        INTEGER NOT NULL,
                      next_attempt_ms INTEGER NOT NULL,
                      status          TEXT NOT NULL
                    )""");
            st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_outbox_pending "
                            + "ON outbox (status, next_attempt_ms, created_at_ms)");
        }
    }

    @Override
    public boolean enqueue(OutboxRecord r) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO outbox "
                        + "(operation_id, event_id, event_json, created_at_ms, attempts, next_attempt_ms, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, r.operationId());
            ps.setString(2, r.eventId());
            ps.setString(3, r.eventJson());
            ps.setLong(4, r.createdAtEpochMs());
            ps.setInt(5, r.attempts());
            ps.setLong(6, r.nextAttemptAtEpochMs());
            ps.setString(7, r.status().name());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to enqueue " + r.operationId(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long pendingCount() {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to count pending outbox rows", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<OutboxRecord> claimBatch(int max, long nowEpochMs) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT operation_id, event_id, event_json, created_at_ms, attempts, next_attempt_ms, status "
                        + "FROM outbox WHERE status = 'PENDING' AND next_attempt_ms <= ? "
                        + "ORDER BY created_at_ms ASC, operation_id ASC LIMIT ?")) {
            ps.setLong(1, nowEpochMs);
            ps.setInt(2, max);
            try (ResultSet rs = ps.executeQuery()) {
                List<OutboxRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to claim outbox batch", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markDelivered(String operationId) {
        update("UPDATE outbox SET status = 'DELIVERED' WHERE operation_id = ?", operationId);
    }

    @Override
    public void recordFailure(String operationId, long nextAttemptAtEpochMs) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE outbox SET attempts = attempts + 1, next_attempt_ms = ? WHERE operation_id = ?")) {
            ps.setLong(1, nextAttemptAtEpochMs);
            ps.setString(2, operationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to record failure for " + operationId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<OutboxRecord> find(String operationId) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT operation_id, event_id, event_json, created_at_ms, attempts, next_attempt_ms, status "
                        + "FROM outbox WHERE operation_id = ?")) {
            ps.setString(1, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to find " + operationId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<OutboxRecord> stuck(int minAttempts, int limit) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT operation_id, event_id, event_json, created_at_ms, attempts, next_attempt_ms, status "
                        + "FROM outbox WHERE status = 'PENDING' AND attempts >= ? "
                        + "ORDER BY attempts DESC, operation_id ASC LIMIT ?")) {
            ps.setInt(1, minAttempts);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<OutboxRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to query stuck outbox rows", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> deleteOldestPending() {
        lock.lock();
        try {
            String oldest = null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT operation_id FROM outbox WHERE status = 'PENDING' "
                            + "ORDER BY created_at_ms ASC, operation_id ASC LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    oldest = rs.getString(1);
                }
            }
            if (oldest == null) {
                return Optional.empty();
            }
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM outbox WHERE operation_id = ?")) {
                del.setString(1, oldest);
                del.executeUpdate();
            }
            return Optional.of(oldest);
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to delete oldest pending row", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            connection.close();
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to close outbox database", e);
        } finally {
            lock.unlock();
        }
    }

    private void update(String sql, String operationId) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new OutboxStorageException("Failed to update " + operationId, e);
        } finally {
            lock.unlock();
        }
    }

    private static OutboxRecord mapRow(ResultSet rs) throws SQLException {
        return new OutboxRecord(
                rs.getString("operation_id"),
                rs.getString("event_id"),
                rs.getString("event_json"),
                rs.getLong("created_at_ms"),
                rs.getInt("attempts"),
                rs.getLong("next_attempt_ms"),
                OutboxStatus.valueOf(rs.getString("status")));
    }
}
