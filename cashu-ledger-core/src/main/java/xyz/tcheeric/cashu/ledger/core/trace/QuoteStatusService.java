package xyz.tcheeric.cashu.ledger.core.trace;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Synthesises a quote's lifecycle (design §5.5 / FR-010) from the events that reference it:
 * {@code succeeded} if a MINT/MELT settled it, {@code failed} if a MINT_FAILED/MELT_FAILED
 * referenced it, {@code expired} if its {@code expires_at} passed with no settlement, otherwise
 * {@code pending}. Also runs the periodic expiry sweep that flips lapsed open quotes to terminal
 * ({@code quote_expired}) using the {@code (quote_expires_at, mint_url)} index.
 */
public final class QuoteStatusService {

    private static final int QUOTE_EVENT_LIMIT = 64;
    static final String REASON_QUOTE_EXPIRED = "quote_expired";

    private final TraceEventStore store;
    private final SqliteSidecarIndex index;
    private final Clock clock;

    public QuoteStatusService(TraceEventStore store, SqliteSidecarIndex index, Clock clock) {
        this.store = store;
        this.index = index;
        this.clock = clock;
    }

    /** The synthesised status for a quote, or empty if no events reference it. */
    public Optional<QuoteStatusView> status(String mintUrl, String quoteId) {
        List<StoredEvent> events = store.findByQuote(mintUrl, quoteId, QUOTE_EVENT_LIMIT);
        if (events.isEmpty()) {
            return Optional.empty();
        }
        Optional<TransactionEvent> request = firstOfKind(events,
                OperationKind.MINT_QUOTE_REQUESTED, OperationKind.MELT_QUOTE_REQUESTED);
        Optional<TransactionEvent> success = firstOfKind(events, OperationKind.MINT, OperationKind.MELT);
        Optional<TransactionEvent> failure = firstOfKind(events,
                OperationKind.MINT_FAILED, OperationKind.MELT_FAILED);
        Optional<Instant> expiresAt = request.flatMap(TransactionEvent::lightning)
                .flatMap(LightningRef::expiresAt);

        QuoteStatusView.Status status;
        String settledEventId = null;
        String failureReason = null;
        if (success.isPresent()) {
            status = QuoteStatusView.Status.SUCCEEDED;
            settledEventId = success.get().eventId().orElse(null);
        } else if (failure.isPresent()) {
            status = QuoteStatusView.Status.FAILED;
            settledEventId = failure.get().eventId().orElse(null);
            failureReason = failure.get().errorMessage().or(failure.get()::errorCode).orElse(null);
        } else if (expiresAt.isPresent() && Instant.now(clock).isAfter(expiresAt.get())) {
            status = QuoteStatusView.Status.EXPIRED;
        } else {
            status = QuoteStatusView.Status.PENDING;
        }

        TransactionEvent anchor = request.orElse(events.get(0).event());
        return Optional.of(new QuoteStatusView(
                quoteId, mintUrl, operation(anchor.kind()),
                status.name().toLowerCase(),
                anchor.transitionAt().toString(),
                expiresAt.map(Instant::toString).orElse(null),
                settledEventId, failureReason));
    }

    /** Flips open quotes whose expiry has passed to terminal ({@code quote_expired}). Returns the count. */
    public int sweepExpired(int limit) {
        long nowS = Instant.now(clock).getEpochSecond();
        List<String> expired = index.expiredOpenQuotes(nowS, limit);
        for (String eventId : expired) {
            index.markEventTerminal(eventId, REASON_QUOTE_EXPIRED, nowS);
        }
        return expired.size();
    }

    private static Optional<TransactionEvent> firstOfKind(List<StoredEvent> events, OperationKind... kinds) {
        return events.stream()
                .map(StoredEvent::event)
                .filter(e -> {
                    for (OperationKind kind : kinds) {
                        if (e.kind() == kind) {
                            return true;
                        }
                    }
                    return false;
                })
                .findFirst();
    }

    private static String operation(OperationKind kind) {
        return kind == OperationKind.MELT_QUOTE_REQUESTED || kind == OperationKind.MELT
                || kind == OperationKind.MELT_FAILED ? "melt" : "mint";
    }
}
