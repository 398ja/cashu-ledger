package xyz.tcheeric.cashu.ledger.trace.publisher;

import java.util.Optional;

/**
 * Maps a domain operation — identified by {@code (operationType, primaryKey)} — to
 * a stable operation id, persisted before the user-facing operation acknowledges
 * success and reused on every retry, restart, and backfill (design FR-19). This is
 * what makes idempotency real: a fresh id per retry would defeat de-duplication.
 */
public interface OperationIdRegistry {

    /**
     * Returns the existing operation id for {@code (operationType, primaryKey)},
     * or atomically generates, stores, and returns a new UUIDv7 if none exists.
     */
    String resolve(String operationType, String primaryKey);

    /** Returns the stored operation id, if one exists. */
    Optional<String> find(String operationType, String primaryKey);
}
