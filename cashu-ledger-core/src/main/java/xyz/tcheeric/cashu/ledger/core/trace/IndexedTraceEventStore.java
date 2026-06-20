package xyz.tcheeric.cashu.ledger.core.trace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import xyz.tcheeric.cashu.ledger.trace.core.EventActivity;
import xyz.tcheeric.cashu.ledger.trace.core.IndexStatus;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventQuery;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;

/**
 * {@link TraceEventStore} backed by a {@link RawEventStore} (system of record for
 * the signed events) and a {@link SqliteSidecarIndex} (derived projections for
 * high-cardinality lookups). Lookups resolve event ids via the index, then fetch
 * payloads from the raw store — matching the design's nostrdb + sidecar split (§5.4),
 * with the raw store pluggable so the logic is testable without the native library.
 */
public final class IndexedTraceEventStore implements TraceEventStore {

    private final RawEventStore rawStore;
    private final SqliteSidecarIndex index;

    public IndexedTraceEventStore(RawEventStore rawStore, SqliteSidecarIndex index) {
        this.rawStore = rawStore;
        this.index = index;
    }

    /**
     * Events present in the raw system-of-record but not yet projected into the sidecar — the
     * index's backlog (design §5.4, {@code pending_index}). Positive only transiently: at startup
     * before rebuild, or after a sidecar write failure; zero once the sidecar has caught up.
     */
    public long pendingIndexCount() {
        return Math.max(0, rawStore.count() - index.count());
    }

    @Override
    public boolean store(StoredEvent event) {
        boolean newlyStored = rawStore.store(event);
        index.index(event.event());
        return newlyStored;
    }

    @Override
    public Optional<StoredEvent> findByEventId(String eventId) {
        Optional<StoredEvent> raw = rawStore.findByEventId(eventId);
        return raw.isPresent() ? raw : skeletonIfTombstoned(eventId);
    }

    /**
     * Prunes an event: removes its raw payload but retains the index/proof_ref rows and records
     * a tombstone, so the hop stays traversable as a pruned-event placeholder (design §5.11).
     */
    public boolean tombstone(String eventId) {
        boolean removed = rawStore.remove(eventId);
        index.recordTombstone(eventId, System.currentTimeMillis() / 1000L);
        return removed;
    }

    private Optional<StoredEvent> skeletonIfTombstoned(String eventId) {
        if (!index.isTombstoned(eventId)) {
            return Optional.empty();
        }
        return index.skeletonEvent(eventId).map(StoredEvent::of);
    }

    @Override
    public Optional<StoredEvent> findByOperationId(String operationId) {
        return index.eventIdForOperation(operationId).flatMap(rawStore::findByEventId);
    }

    @Override
    public List<StoredEvent> findByInputProofRef(String mintUrl, String keysetId, String y, int limit) {
        return resolve(index.byProofRef("input", mintUrl, keysetId, y, limit));
    }

    @Override
    public List<StoredEvent> findByOutputProofRef(String mintUrl, String keysetId, String y, int limit) {
        return resolve(index.byProofRef("output", mintUrl, keysetId, y, limit));
    }

    @Override
    public List<StoredEvent> findByMintUrl(String mintUrl, Instant since, Instant until, int limit) {
        return resolve(index.findFiltered(TraceEventQuery.builder()
                .mintUrl(mintUrl).since(since).until(until).limit(limit).build()));
    }

    @Override
    public List<StoredEvent> findByProducer(String pubkey, Instant since, Instant until, int limit) {
        return resolve(index.findFiltered(TraceEventQuery.builder()
                .producerPubkey(pubkey).since(since).until(until).limit(limit).build()));
    }

    @Override
    public List<StoredEvent> findByInitiator(String pubkey, Instant since, Instant until, int limit) {
        return resolve(index.findFiltered(TraceEventQuery.builder()
                .initiatorPubkey(pubkey).since(since).until(until).limit(limit).build()));
    }

    @Override
    public List<StoredEvent> findByVoucherRef(String voucherId, int limit) {
        return resolve(index.findFiltered(TraceEventQuery.builder()
                .voucherRef(voucherId).limit(limit).build()));
    }

    @Override
    public List<StoredEvent> findByIssuerId(String issuerId, Instant since, Instant until, int limit) {
        return resolve(index.findFiltered(TraceEventQuery.builder()
                .issuerId(issuerId).since(since).until(until).limit(limit).build()));
    }

    @Override
    public List<StoredEvent> findByIssuerPubkey(String issuerPubkey, Instant since, Instant until, int limit) {
        return resolve(index.findFiltered(TraceEventQuery.builder()
                .issuerPubkey(issuerPubkey).since(since).until(until).limit(limit).build()));
    }

    @Override
    public List<StoredEvent> findByQuote(String mintUrl, String quoteId, int limit) {
        return resolve(index.findFiltered(TraceEventQuery.builder()
                .quoteId(mintUrl + "::" + quoteId).limit(limit).build()));
    }

    @Override
    public List<StoredEvent> findByBundleId(String bundleId, int limit) {
        return resolve(index.findFiltered(TraceEventQuery.builder()
                .bundleId(bundleId).limit(limit).build()));
    }

    @Override
    public List<StoredEvent> findByTransferId(String transferId, int limit) {
        return resolve(index.findFiltered(TraceEventQuery.builder()
                .transferId(transferId).limit(limit).build()));
    }

    @Override
    public EventActivity getActivity(String eventId) {
        return index.activity(eventId).orElseGet(() -> rawStore.findByEventId(eventId)
                .map(stored -> stored.event().kind().isTerminalKind()
                        ? EventActivity.TERMINAL : EventActivity.ACTIVE)
                .orElse(EventActivity.ACTIVE));
    }

    @Override
    public List<StoredEvent> findFiltered(TraceEventQuery query) {
        Cursor after = query.cursor().flatMap(Cursor::decode).orElse(null);
        return resolve(index.findFiltered(query, after));
    }

    @Override
    public IndexStatus getIndexStatus() {
        return new IndexStatus(true, false, index.count(), index.latestTransitionAt());
    }

    private List<StoredEvent> resolve(List<String> eventIds) {
        return eventIds.stream()
                .map(this::findByEventId)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }
}
