package xyz.tcheeric.cashu.ledger.core.trace;

import java.time.Clock;
import java.time.Instant;
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Maintains the derived {@code activity}/{@code activity_reason} columns in the sidecar
 * (design §5.4.1). Terminal-kind events are classified at ingest by the sidecar itself;
 * this cache applies the three event-driven invalidation triggers:
 *
 * <ol>
 *   <li><b>Downstream spend</b> — when a new event consumes the outputs of an existing
 *       one and all of that producer's outputs are now spent, the producer flips to
 *       {@code all_outputs_spent}.</li>
 *   <li><b>Voucher state change</b> — when a watched voucher turns terminal, every event
 *       bound to it flips to {@code voucher_terminal} (via {@link VoucherTerminalListener}).</li>
 *   <li><b>Quote settlement / expiry</b> — when a settlement (or {@code *_FAILED}) event
 *       arrives for an open quote, the quote-only event flips to {@code quote_settled} or
 *       {@code quote_expired}.</li>
 * </ol>
 *
 * <p>Periodic quote-expiry sweeps without a settlement event are handled separately; this
 * component covers the event-driven path.</p>
 */
public final class ActivityCache implements VoucherTerminalListener {

    static final String REASON_ALL_OUTPUTS_SPENT = "all_outputs_spent";
    static final String REASON_VOUCHER_TERMINAL = "voucher_terminal";
    static final String REASON_QUOTE_SETTLED = "quote_settled";
    static final String REASON_QUOTE_EXPIRED = "quote_expired";
    private static final String QUOTE_EXPIRED_CODE = "QUOTE_EXPIRED";

    private final SqliteSidecarIndex index;
    private final Clock clock;

    public ActivityCache(SqliteSidecarIndex index) {
        this(index, Clock.systemUTC());
    }

    public ActivityCache(SqliteSidecarIndex index, Clock clock) {
        this.index = index;
        this.clock = clock;
    }

    /** Applies the downstream-spend and quote-settlement triggers for a freshly ingested event. */
    public void onEventIngested(TransactionEvent event) {
        terminaliseFullySpentProducers(event);
        terminaliseSettledQuote(event);
    }

    @Override
    public void onVoucherTerminal(VoucherStatusObservation observation) {
        index.markVoucherEventsTerminal(observation.voucherId(), REASON_VOUCHER_TERMINAL, nowSeconds());
    }

    private void terminaliseFullySpentProducers(TransactionEvent event) {
        if (!event.kind().isMintStateChange()) {
            return;
        }
        for (ProofRef input : event.inputs()) {
            index.producerEventOf(event.mintUrl(), input.keysetId(), input.y())
                    .filter(index::allOutputsSpent)
                    .ifPresent(producer ->
                            index.markEventTerminal(producer, REASON_ALL_OUTPUTS_SPENT, nowSeconds()));
        }
    }

    private void terminaliseSettledQuote(TransactionEvent event) {
        if (!isQuoteSettlement(event.kind())) {
            return;
        }
        event.lightning().map(LightningRef::quoteId).ifPresent(quoteId ->
                index.markQuoteEventsTerminal(event.mintUrl(), quoteId, settlementReason(event), nowSeconds()));
    }

    private static boolean isQuoteSettlement(OperationKind kind) {
        return switch (kind) {
            case MINT, MELT, MINT_FAILED, MELT_FAILED -> true;
            default -> false;
        };
    }

    private static String settlementReason(TransactionEvent event) {
        return event.errorCode().filter(QUOTE_EXPIRED_CODE::equals).isPresent()
                ? REASON_QUOTE_EXPIRED : REASON_QUOTE_SETTLED;
    }

    private long nowSeconds() {
        return Instant.now(clock).getEpochSecond();
    }
}
