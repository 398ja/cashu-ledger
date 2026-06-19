package xyz.tcheeric.cashu.ledger.core.trace;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import xyz.tcheeric.cashu.ledger.trace.core.EventActivity;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventQuery;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * SQLite sidecar index over the raw trace events (design §5.4). Holds derived
 * projections — one {@code events_index} row per event plus {@code proof_ref} rows
 * per input/output — keyed for the high-cardinality lookups that nostrdb cannot
 * serve efficiently. Returns event ids; the {@link RawEventStore} resolves payloads.
 *
 * <p>This first cut computes activity from the operation kind only (terminal kinds
 * are terminal); the full §5.4.1 cache and its invalidation triggers arrive with
 * the voucher-state watcher.</p>
 */
public final class SqliteSidecarIndex implements AutoCloseable {

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    public SqliteSidecarIndex(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            initSchema();
        } catch (SQLException e) {
            throw new TraceStorageException("Failed to open sidecar index at " + jdbcUrl, e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS events_index (
                      event_id        TEXT PRIMARY KEY,
                      operation_id    TEXT,
                      op              TEXT NOT NULL,
                      mint_url        TEXT NOT NULL,
                      unit            TEXT,
                      transition_at_ms INTEGER NOT NULL,
                      created_at_s    INTEGER NOT NULL,
                      producer_pubkey TEXT,
                      initiator_pubkey TEXT,
                      voucher_ref     TEXT,
                      issuer_id       TEXT,
                      issuer_pubkey   TEXT,
                      quote_id        TEXT,
                      bundle_id       TEXT,
                      transfer_id     TEXT,
                      activity        TEXT NOT NULL DEFAULT 'active',
                      activity_reason TEXT
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS proof_ref (
                      event_id  TEXT NOT NULL,
                      role      TEXT NOT NULL,
                      position  INTEGER NOT NULL,
                      mint_url  TEXT NOT NULL,
                      keyset_id TEXT NOT NULL,
                      y         TEXT NOT NULL,
                      PRIMARY KEY (event_id, role, position)
                    )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_idx_mint ON events_index (mint_url, transition_at_ms)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_idx_producer ON events_index (producer_pubkey, transition_at_ms)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_idx_initiator ON events_index (initiator_pubkey, transition_at_ms)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_idx_voucher ON events_index (voucher_ref)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_idx_issuer_id ON events_index (issuer_id, transition_at_ms)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_idx_issuer_pk ON events_index (issuer_pubkey, transition_at_ms)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_idx_quote ON events_index (quote_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_idx_bundle ON events_index (bundle_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_idx_transfer ON events_index (transfer_id, transition_at_ms)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_idx_op ON events_index (operation_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_idx_activity ON events_index (activity, transition_at_ms)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pr_lookup ON proof_ref (role, mint_url, keyset_id, y)");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS voucher_status (
                      voucher_id    TEXT PRIMARY KEY,
                      status        TEXT NOT NULL,
                      state_version INTEGER NOT NULL,
                      transition_at_s INTEGER NOT NULL,
                      terminal      INTEGER NOT NULL DEFAULT 0,
                      issuer_id     TEXT,
                      issuer_pubkey TEXT
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS watcher_cursor (
                      name     TEXT PRIMARY KEY,
                      position INTEGER NOT NULL
                    )""");
        }
    }

    /** Projects an event into the index. Idempotent; returns {@code true} if newly indexed. */
    public boolean index(TransactionEvent e) {
        String eventId = e.eventId().orElseThrow(() ->
                new IllegalArgumentException("event must have a present eventId to be indexed"));
        lock.lock();
        try {
            boolean terminal = e.kind().isTerminalKind();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO events_index (event_id, operation_id, op, mint_url, unit, "
                            + "transition_at_ms, created_at_s, producer_pubkey, initiator_pubkey, voucher_ref, "
                            + "issuer_id, issuer_pubkey, quote_id, bundle_id, transfer_id, activity, activity_reason) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, eventId);
                ps.setString(2, e.operationId());
                ps.setString(3, e.kind().wireValue());
                ps.setString(4, e.mintUrl());
                ps.setString(5, e.unit());
                ps.setLong(6, e.transitionAt().toEpochMilli());
                ps.setLong(7, e.createdAt().getEpochSecond());
                ps.setString(8, e.producerPubkey());
                ps.setString(9, e.initiatorPubkey().orElse(null));
                ps.setString(10, e.voucherRef().orElse(null));
                ps.setString(11, e.issuerId().orElse(null));
                ps.setString(12, e.issuerPubkey().orElse(null));
                ps.setString(13, e.lightning().map(l -> e.mintUrl() + "::" + l.quoteId()).orElse(null));
                ps.setString(14, e.bundleId().orElse(null));
                ps.setString(15, e.transferId().orElse(null));
                ps.setString(16, terminal ? "terminal" : "active");
                ps.setString(17, terminal ? "terminal_kind" : null);
                if (ps.executeUpdate() == 0) {
                    return false; // already indexed
                }
            }
            indexProofs(eventId, "input", e.inputs(), e.mintUrl());
            indexProofs(eventId, "output", e.outputs(), e.mintUrl());
            return true;
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed to index event " + eventId, ex);
        } finally {
            lock.unlock();
        }
    }

    private void indexProofs(String eventId, String role, List<ProofRef> proofs, String mintUrl)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO proof_ref (event_id, role, position, mint_url, keyset_id, y) "
                        + "VALUES (?,?,?,?,?,?)")) {
            for (int i = 0; i < proofs.size(); i++) {
                ProofRef p = proofs.get(i);
                ps.setString(1, eventId);
                ps.setString(2, role);
                ps.setInt(3, i);
                ps.setString(4, mintUrl);
                ps.setString(5, p.keysetId());
                ps.setString(6, p.y());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Records a voucher-state observation, keeping the highest {@code state_version}
     * seen. Returns {@code true} only when this observation moves the voucher into a
     * terminal status for the first time (the trigger for activity-cache invalidation).
     */
    public boolean upsertVoucherStatus(VoucherStatusObservation obs) {
        lock.lock();
        try {
            long existingVersion = -1L;
            boolean wasTerminal = false;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT state_version, terminal FROM voucher_status WHERE voucher_id = ?")) {
                ps.setString(1, obs.voucherId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existingVersion = rs.getLong(1);
                        wasTerminal = rs.getInt(2) == 1;
                    }
                }
            }
            if (obs.stateVersion() <= existingVersion) {
                return false; // stale or duplicate revision
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO voucher_status (voucher_id, status, state_version, transition_at_s, "
                            + "terminal, issuer_id, issuer_pubkey) VALUES (?,?,?,?,?,?,?) "
                            + "ON CONFLICT(voucher_id) DO UPDATE SET status=excluded.status, "
                            + "state_version=excluded.state_version, transition_at_s=excluded.transition_at_s, "
                            + "terminal=excluded.terminal, issuer_id=excluded.issuer_id, "
                            + "issuer_pubkey=excluded.issuer_pubkey")) {
                ps.setString(1, obs.voucherId());
                ps.setString(2, obs.status());
                ps.setLong(3, obs.stateVersion());
                ps.setLong(4, obs.transitionAt().getEpochSecond());
                ps.setInt(5, obs.terminal() ? 1 : 0);
                ps.setString(6, obs.issuerId().orElse(null));
                ps.setString(7, obs.issuerPubkey().orElse(null));
                ps.executeUpdate();
            }
            return obs.terminal() && !wasTerminal;
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed to record voucher status " + obs.voucherId(), ex);
        } finally {
            lock.unlock();
        }
    }

    /** The latest recorded status for a voucher, if any has been observed. */
    public Optional<VoucherStatusObservation> loadVoucherStatus(String voucherId) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT status, state_version, transition_at_s, terminal, issuer_id, issuer_pubkey "
                        + "FROM voucher_status WHERE voucher_id = ?")) {
            ps.setString(1, voucherId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new VoucherStatusObservation(
                        voucherId, rs.getString(1), rs.getLong(2),
                        Instant.ofEpochSecond(rs.getLong(3)), rs.getInt(4) == 1,
                        Optional.ofNullable(rs.getString(5)), Optional.ofNullable(rs.getString(6))));
            }
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed to load voucher status " + voucherId, ex);
        } finally {
            lock.unlock();
        }
    }

    /** Loads a named watcher cursor position (e.g. a relay's last-seen created_at). */
    public Optional<Long> loadCursor(String name) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT position FROM watcher_cursor WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed to load watcher cursor " + name, ex);
        } finally {
            lock.unlock();
        }
    }

    /** Persists a named watcher cursor position. */
    public void saveCursor(String name, long position) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO watcher_cursor (name, position) VALUES (?,?) "
                        + "ON CONFLICT(name) DO UPDATE SET position=excluded.position")) {
            ps.setString(1, name);
            ps.setLong(2, position);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed to save watcher cursor " + name, ex);
        } finally {
            lock.unlock();
        }
    }

    public Optional<String> eventIdForOperation(String operationId) {
        return querySingle("SELECT event_id FROM events_index WHERE operation_id = ?", operationId);
    }

    public List<String> byProofRef(String role, String mintUrl, String keysetId, String y, int limit) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT pr.event_id FROM proof_ref pr JOIN events_index e ON e.event_id = pr.event_id "
                        + "WHERE pr.role = ? AND pr.mint_url = ? AND pr.keyset_id = ? AND pr.y = ? "
                        + "ORDER BY e.transition_at_ms DESC, e.event_id DESC LIMIT ?")) {
            ps.setString(1, role);
            ps.setString(2, mintUrl);
            ps.setString(3, keysetId);
            ps.setString(4, y);
            ps.setInt(5, limit);
            return collectEventIds(ps);
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed proof-ref lookup", ex);
        } finally {
            lock.unlock();
        }
    }

    /** General filtered listing (design §5.5), without a cursor. */
    public List<String> findFiltered(TraceEventQuery q) {
        return findFiltered(q, null);
    }

    /**
     * General filtered listing with optional seek cursor. Results are ordered
     * {@code (transition_at DESC, event_id DESC)}; {@code after}, when present, returns
     * only rows strictly older than that position.
     */
    public List<String> findFiltered(TraceEventQuery q, Cursor after) {
        StringBuilder sql = new StringBuilder("SELECT event_id FROM events_index WHERE 1=1");
        List<Object> args = new ArrayList<>();
        q.mintUrl().ifPresent(v -> { sql.append(" AND mint_url = ?"); args.add(v); });
        q.producerPubkey().ifPresent(v -> { sql.append(" AND producer_pubkey = ?"); args.add(v); });
        q.initiatorPubkey().ifPresent(v -> { sql.append(" AND initiator_pubkey = ?"); args.add(v); });
        q.issuerId().ifPresent(v -> { sql.append(" AND issuer_id = ?"); args.add(v); });
        q.issuerPubkey().ifPresent(v -> { sql.append(" AND issuer_pubkey = ?"); args.add(v); });
        q.voucherRef().ifPresent(v -> { sql.append(" AND voucher_ref = ?"); args.add(v); });
        q.quoteId().ifPresent(v -> { sql.append(" AND quote_id = ?"); args.add(v); });
        q.transferId().ifPresent(v -> { sql.append(" AND transfer_id = ?"); args.add(v); });
        q.bundleId().ifPresent(v -> { sql.append(" AND bundle_id = ?"); args.add(v); });
        q.kind().ifPresent(v -> { sql.append(" AND op = ?"); args.add(v.wireValue()); });
        q.since().ifPresent(v -> { sql.append(" AND transition_at_ms >= ?"); args.add(v.toEpochMilli()); });
        q.until().ifPresent(v -> { sql.append(" AND transition_at_ms < ?"); args.add(v.toEpochMilli()); });
        q.activity().ifPresent(v -> { sql.append(" AND activity = ?"); args.add(v == EventActivity.TERMINAL ? "terminal" : "active"); });
        if (after != null) {
            sql.append(" AND (transition_at_ms < ? OR (transition_at_ms = ? AND event_id < ?))");
            args.add(after.transitionAtMs());
            args.add(after.transitionAtMs());
            args.add(after.eventId());
        }
        sql.append(" ORDER BY transition_at_ms DESC, event_id DESC LIMIT ?");
        args.add(q.limit());

        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bind(ps, args);
            return collectEventIds(ps);
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed filtered listing", ex);
        } finally {
            lock.unlock();
        }
    }

    /** Distinct {@code (mint_url, keyset_id)} pairs that carry a proof with this {@code y}. */
    public List<ProofCandidate> candidatesForY(String y) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT mint_url, keyset_id FROM proof_ref WHERE y = ? ORDER BY mint_url, keyset_id")) {
            ps.setString(1, y);
            try (ResultSet rs = ps.executeQuery()) {
                List<ProofCandidate> candidates = new ArrayList<>();
                while (rs.next()) {
                    candidates.add(new ProofCandidate(rs.getString(1), rs.getString(2)));
                }
                return candidates;
            }
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed y-candidate lookup", ex);
        } finally {
            lock.unlock();
        }
    }

    /** Chronological (oldest-first) input/output occurrences of a proof tuple. */
    public List<ProofRefRow> proofRefRows(String mintUrl, String keysetId, String y) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT pr.event_id, pr.role, e.transition_at_ms FROM proof_ref pr "
                        + "JOIN events_index e ON e.event_id = pr.event_id "
                        + "WHERE pr.mint_url = ? AND pr.keyset_id = ? AND pr.y = ? "
                        + "ORDER BY e.transition_at_ms ASC, e.event_id ASC")) {
            ps.setString(1, mintUrl);
            ps.setString(2, keysetId);
            ps.setString(3, y);
            try (ResultSet rs = ps.executeQuery()) {
                List<ProofRefRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new ProofRefRow(rs.getString(1), rs.getString(2), rs.getLong(3)));
                }
                return rows;
            }
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed proof-ref row lookup", ex);
        } finally {
            lock.unlock();
        }
    }

    public Optional<EventActivity> activity(String eventId) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT activity FROM events_index WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of("terminal".equals(rs.getString(1))
                        ? EventActivity.TERMINAL : EventActivity.ACTIVE);
            }
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed to read activity for " + eventId, ex);
        } finally {
            lock.unlock();
        }
    }

    public long count() {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM events_index");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed to count indexed events", ex);
        } finally {
            lock.unlock();
        }
    }

    public Optional<Instant> latestTransitionAt() {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT MAX(transition_at_ms) FROM events_index");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                long v = rs.getLong(1);
                if (!rs.wasNull()) {
                    return Optional.of(Instant.ofEpochMilli(v));
                }
            }
            return Optional.empty();
        } catch (SQLException ex) {
            throw new TraceStorageException("Failed to read latest transition_at", ex);
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
            throw new TraceStorageException("Failed to close sidecar index", e);
        } finally {
            lock.unlock();
        }
    }

    private Optional<String> querySingle(String sql, String arg) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new TraceStorageException("Query failed: " + sql, ex);
        } finally {
            lock.unlock();
        }
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object a = args.get(i);
            if (a instanceof Long l) {
                ps.setLong(i + 1, l);
            } else if (a instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else {
                ps.setString(i + 1, (String) a);
            }
        }
    }

    private static List<String> collectEventIds(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<String> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
            return ids;
        }
    }
}
