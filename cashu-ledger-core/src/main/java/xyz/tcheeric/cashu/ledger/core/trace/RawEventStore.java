package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.Optional;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;

/**
 * System-of-record store for the raw signed trace events. nostrdb is the
 * production implementation; an in-memory implementation backs unit tests so the
 * indexing/query logic can be exercised without the native library.
 *
 * <p>The {@link xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent#eventId()} of
 * a stored event MUST be present — it is the storage key.</p>
 */
public interface RawEventStore {

    /**
     * Stores a signed event idempotently. Returns {@code true} if newly stored,
     * {@code false} if an event with the same id already existed.
     */
    boolean store(StoredEvent event);

    Optional<StoredEvent> findByEventId(String eventId);

    /**
     * Removes a stored raw event (e.g. a retention prune). Returns {@code true} if an event
     * was removed. Index and tombstone rows are managed separately by the caller.
     */
    boolean remove(String eventId);

    /** Number of events held. */
    long count();
}
