package xyz.tcheeric.cashu.ledger.core.trace;

import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;

/**
 * Notified after the ingest pipeline stores a new event, so serving-side concerns can react
 * without the ingest path depending on them: the activity cache re-evaluates affected events
 * and the SSE stream fans out a notification (design §5.4.1 / §5.5). Only fired for events
 * that are newly stored — never for duplicates or rejections.
 */
@FunctionalInterface
public interface TraceIngestListener {

    /** A no-op listener for deployments that ingest without serving-side reactions. */
    TraceIngestListener NONE = event -> { };

    void onStored(StoredEvent event);
}
