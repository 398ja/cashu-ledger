package xyz.tcheeric.cashu.ledger.trace.core;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The storage port for transaction events — the boundary the ledger implements
 * (over nostrdb + the SQLite sidecar) and that read services depend on. Expressed
 * entirely in trace-core types so this module stays free of Nostr/storage
 * dependencies; the ledger adapter bridges to the signed Nostr event form.
 *
 * <p>Proof lookups take the full {@code (mintUrl, keysetId, y)} tuple (FR-4); a
 * caller holding only {@code y} resolves candidates at a higher layer.</p>
 */
public interface TraceEventStore {

    /**
     * Persists an event idempotently. Returns {@code true} if newly stored,
     * {@code false} if an event with the same id already existed (no-op).
     */
    boolean store(StoredEvent event);

    Optional<StoredEvent> findByEventId(String eventId);

    Optional<StoredEvent> findByOperationId(String operationId);

    /** Events that reference the given proof tuple as an input. */
    List<StoredEvent> findByInputProofRef(String mintUrl, String keysetId, String y, int limit);

    /** Events that reference the given proof tuple as an output. */
    List<StoredEvent> findByOutputProofRef(String mintUrl, String keysetId, String y, int limit);

    List<StoredEvent> findByMintUrl(String mintUrl, Instant since, Instant until, int limit);

    List<StoredEvent> findByProducer(String pubkey, Instant since, Instant until, int limit);

    List<StoredEvent> findByInitiator(String pubkey, Instant since, Instant until, int limit);

    List<StoredEvent> findByVoucherRef(String voucherId, int limit);

    List<StoredEvent> findByIssuerId(String issuerId, Instant since, Instant until, int limit);

    List<StoredEvent> findByIssuerPubkey(String issuerPubkey, Instant since, Instant until, int limit);

    /** @param quoteId the raw producer quote id; {@code mintUrl} disambiguates cross-mint collisions */
    List<StoredEvent> findByQuote(String mintUrl, String quoteId, int limit);

    List<StoredEvent> findByBundleId(String bundleId, int limit);

    List<StoredEvent> findByTransferId(String transferId, int limit);

    /**
     * The cached activity classification for an event (design §5.4.1), recomputed
     * on demand if the cached value is stale.
     */
    EventActivity getActivity(String eventId);

    /** General-purpose filtered listing; see {@link TraceEventQuery}. */
    List<StoredEvent> findFiltered(TraceEventQuery query);

    IndexStatus getIndexStatus();
}
