package xyz.tcheeric.cashu.ledger.core.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import xyz.tcheeric.cashu.ledger.trace.core.EventActivity;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventQuery;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Read orchestration over the {@link TraceEventStore} and sidecar: single-event and
 * by-operation lookups, cursor-paginated listings, and proof history (single or
 * grouped per {@code (mintUrl, keysetId)}). The web and CLI layers depend on this
 * service rather than the store directly (design §5.5).
 */
public final class TraceQueryService {

    private final TraceEventStore store;
    private final SqliteSidecarIndex index;

    public TraceQueryService(TraceEventStore store, SqliteSidecarIndex index) {
        this.store = store;
        this.index = index;
    }

    public Optional<StoredEvent> getEvent(String eventId) {
        return store.findByEventId(eventId);
    }

    public Optional<StoredEvent> getByOperation(String operationId) {
        return store.findByOperationId(operationId);
    }

    /** A cursor-paginated listing; fetches one extra row to determine the next cursor. */
    public EventPage listEvents(TraceEventQuery query) {
        int limit = query.limit();
        List<StoredEvent> fetched = store.findFiltered(withLimit(query, limit + 1));
        if (fetched.size() <= limit) {
            return new EventPage(fetched, Optional.empty());
        }
        List<StoredEvent> page = new ArrayList<>(fetched.subList(0, limit));
        TransactionEvent last = page.get(limit - 1).event();
        String cursor = new Cursor(last.transitionAt().toEpochMilli(), last.eventId().orElseThrow()).encode();
        return new EventPage(page, Optional.of(cursor));
    }

    /** The distinct {@code (mintUrl, keysetId)} pairs that carry this {@code y}. */
    public List<ProofCandidate> candidatesForY(String y) {
        return index.candidatesForY(y);
    }

    /** Cursor-paginated events that reference a given voucher, newest first. */
    public EventPage findByVoucherRef(String voucherRef, Optional<EventActivity> activity,
                                      int limit, Optional<String> cursor) {
        TraceEventQuery.Builder b = listingBuilder(activity, limit, cursor).voucherRef(voucherRef);
        return listEvents(b.build());
    }

    /** Cursor-paginated events attributed to a given issuer id, newest first. */
    public EventPage findByIssuerId(String issuerId, Optional<EventActivity> activity,
                                    int limit, Optional<String> cursor) {
        TraceEventQuery.Builder b = listingBuilder(activity, limit, cursor).issuerId(issuerId);
        return listEvents(b.build());
    }

    /** Cursor-paginated events attributed to a given issuer pubkey, newest first. */
    public EventPage findByIssuerPubkey(String issuerPubkey, Optional<EventActivity> activity,
                                        int limit, Optional<String> cursor) {
        TraceEventQuery.Builder b = listingBuilder(activity, limit, cursor).issuerPubkey(issuerPubkey);
        return listEvents(b.build());
    }

    private static TraceEventQuery.Builder listingBuilder(Optional<EventActivity> activity,
                                                          int limit, Optional<String> cursor) {
        TraceEventQuery.Builder b = TraceEventQuery.builder().limit(limit);
        activity.ifPresent(b::activity);
        cursor.ifPresent(b::cursor);
        return b;
    }

    /**
     * Proof history. With {@code mintUrl} and {@code keysetId} present, returns a
     * single group; otherwise groups per {@code (mintUrl, keysetId)} (optionally
     * narrowed to a given {@code mintUrl}). Never merges proofs across mints.
     */
    public ProofHistory proofHistory(Optional<String> mintUrl, Optional<String> keysetId, String y) {
        if (mintUrl.isPresent() && keysetId.isPresent()) {
            return new ProofHistory(y, List.of(buildGroup(mintUrl.get(), keysetId.get(), y)));
        }
        List<ProofCandidate> candidates = index.candidatesForY(y).stream()
                .filter(c -> mintUrl.isEmpty() || mintUrl.get().equals(c.mintUrl()))
                .toList();
        List<ProofHistory.Group> groups = candidates.stream()
                .map(c -> buildGroup(c.mintUrl(), c.keysetId(), y))
                .toList();
        return new ProofHistory(y, groups);
    }

    private ProofHistory.Group buildGroup(String mintUrl, String keysetId, String y) {
        List<ProofRefRow> rows = index.proofRefRows(mintUrl, keysetId, y);
        List<ProofHistory.Entry> entries = new ArrayList<>(rows.size());
        String originEventId = null;
        boolean spent = false;
        for (ProofRefRow row : rows) {
            OperationKind kind = store.findByEventId(row.eventId())
                    .map(s -> s.event().kind()).orElse(null);
            entries.add(new ProofHistory.Entry(row.eventId(), kind, row.role(),
                    Instant.ofEpochMilli(row.transitionAtMs())));
            if (originEventId == null && "output".equals(row.role())) {
                originEventId = row.eventId();
            }
            if ("input".equals(row.role()) && kind != null && kind.isEdgeConsumer()) {
                spent = true;
            }
        }
        if (originEventId == null && !rows.isEmpty()) {
            originEventId = rows.get(0).eventId();
        }
        Optional<String> terminal = rows.isEmpty()
                ? Optional.empty() : Optional.of(rows.get(rows.size() - 1).eventId());
        return new ProofHistory.Group(mintUrl, keysetId, y, entries,
                Optional.ofNullable(originEventId), terminal, spent);
    }

    private static TraceEventQuery withLimit(TraceEventQuery q, int limit) {
        TraceEventQuery.Builder b = TraceEventQuery.builder().limit(limit);
        q.mintUrl().ifPresent(b::mintUrl);
        q.producerPubkey().ifPresent(b::producerPubkey);
        q.initiatorPubkey().ifPresent(b::initiatorPubkey);
        q.issuerId().ifPresent(b::issuerId);
        q.issuerPubkey().ifPresent(b::issuerPubkey);
        q.voucherRef().ifPresent(b::voucherRef);
        q.quoteId().ifPresent(b::quoteId);
        q.transferId().ifPresent(b::transferId);
        q.bundleId().ifPresent(b::bundleId);
        q.kind().ifPresent(b::kind);
        q.since().ifPresent(b::since);
        q.until().ifPresent(b::until);
        q.activity().ifPresent(b::activity);
        q.cursor().ifPresent(b::cursor);
        return b.build();
    }
}
