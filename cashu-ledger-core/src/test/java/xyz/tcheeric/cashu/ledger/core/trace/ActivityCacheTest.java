package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Unit tests for {@link ActivityCache}: the downstream-spend, voucher-terminal, and
 * quote-settlement/expiry invalidation triggers that maintain the sidecar activity columns.
 */
class ActivityCacheTest {

    private static final String MINT = "https://mint.imani.casa";

    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;
    private ActivityCache cache;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        cache = new ActivityCache(index);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    /** When every output of an upstream event is consumed, it flips to all_outputs_spent. */
    @Test
    void shouldTerminaliseProducerWhenAllOutputsSpent() {
        // Given: a MINT producing one proof, then a SWAP consuming it
        store(event("e-mint", OperationKind.MINT, List.of(), List.of(proof(0)), null, null, null));
        TransactionEvent swap = event("e-swap", OperationKind.SWAP,
                List.of(proof(0)), List.of(proof(1)), null, null, null);
        store(swap);

        // When: the cache processes the downstream spend
        cache.onEventIngested(swap);

        // Then: the MINT is terminal with reason all_outputs_spent
        assertThat(index.activityOf("e-mint")).get()
                .extracting(SqliteSidecarIndex.ActivityState::reason)
                .isEqualTo(Optional.of("all_outputs_spent"));
    }

    /** An upstream event with an unspent output remains active. */
    @Test
    void shouldKeepProducerActiveWhenOutputsPartiallySpent() {
        // Given: a MINT with two outputs, a SWAP consuming only the first
        store(event("e-mint", OperationKind.MINT, List.of(), List.of(proof(0), proof(1)), null, null, null));
        TransactionEvent swap = event("e-swap", OperationKind.SWAP,
                List.of(proof(0)), List.of(proof(2)), null, null, null);
        store(swap);

        // When: the cache processes the spend
        cache.onEventIngested(swap);

        // Then: the MINT stays active
        assertThat(index.activityOf("e-mint")).get()
                .extracting(SqliteSidecarIndex.ActivityState::isTerminal).isEqualTo(false);
    }

    /** A terminal voucher transition flips every event bound to that voucher. */
    @Test
    void shouldTerminaliseEventsWhenVoucherBecomesTerminal() {
        // Given: a SEND referencing voucher v1
        store(event("e-send", OperationKind.SEND, List.of(proof(0)), List.of(), "v1", null, null));

        // When: voucher v1 turns terminal
        cache.onVoucherTerminal(new VoucherStatusObservation(
                "v1", "redeemed", 2, Instant.EPOCH, true, Optional.empty(), Optional.empty()));

        // Then: the SEND is terminal with reason voucher_terminal
        assertThat(index.activityOf("e-send")).get()
                .extracting(SqliteSidecarIndex.ActivityState::reason)
                .isEqualTo(Optional.of("voucher_terminal"));
    }

    /** A settlement event flips its open quote-only event to quote_settled. */
    @Test
    void shouldTerminaliseQuoteWhenSettlementArrives() {
        // Given: an open mint quote, then the MINT settling it
        store(event("e-quote", OperationKind.MINT_QUOTE_REQUESTED, List.of(), List.of(),
                null, "q1", null));
        TransactionEvent mint = event("e-mint", OperationKind.MINT, List.of(), List.of(proof(0)),
                null, "q1", null);
        store(mint);

        // When: the cache processes the settlement
        cache.onEventIngested(mint);

        // Then: the quote event is terminal with reason quote_settled
        assertThat(index.activityOf("e-quote")).get()
                .extracting(SqliteSidecarIndex.ActivityState::reason)
                .isEqualTo(Optional.of("quote_settled"));
    }

    /** A QUOTE_EXPIRED failure flips its quote-only event to quote_expired. */
    @Test
    void shouldTerminaliseQuoteAsExpiredOnQuoteExpiredFailure() {
        // Given: an open melt quote, then a MELT_FAILED with error QUOTE_EXPIRED
        store(event("e-quote", OperationKind.MELT_QUOTE_REQUESTED, List.of(), List.of(),
                null, "q2", null));
        TransactionEvent failed = event("e-failed", OperationKind.MELT_FAILED, List.of(), List.of(),
                null, "q2", "QUOTE_EXPIRED");
        store(failed);

        // When: the cache processes the failure
        cache.onEventIngested(failed);

        // Then: the quote event is terminal with reason quote_expired
        assertThat(index.activityOf("e-quote")).get()
                .extracting(SqliteSidecarIndex.ActivityState::reason)
                .isEqualTo(Optional.of("quote_expired"));
    }

    private void store(TransactionEvent event) {
        store.store(StoredEvent.of(event));
    }

    private static TransactionEvent event(String eventId, OperationKind kind, List<ProofRef> inputs,
                                          List<ProofRef> outputs, String voucherRef, String quoteId,
                                          String errorCode) {
        OperationKind quoteOp = switch (kind) {
            case MELT, MELT_FAILED, MELT_QUOTE_REQUESTED -> OperationKind.MELT_QUOTE_REQUESTED;
            default -> OperationKind.MINT_QUOTE_REQUESTED;
        };
        Optional<LightningRef> lightning = quoteId == null ? Optional.empty()
                : Optional.of(new LightningRef(quoteId, MINT, Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), quoteOp, false));
        return new TransactionEvent(
                Optional.of(eventId), "op-" + eventId, kind, MINT, "sat",
                Instant.EPOCH, Instant.EPOCH, "pk", Optional.empty(), inputs, outputs, List.of(),
                lightning, Optional.ofNullable(voucherRef), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(errorCode),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(eventId), 9079, Optional.empty(),
                        Optional.empty(), Instant.EPOCH));
    }

    private static ProofRef proof(int i) {
        return new ProofRef(8, "ks", y(i), Optional.of("s" + i),
                Optional.of("c" + i), Optional.empty(), Optional.empty());
    }

    private static String y(int i) {
        return "02" + String.format("%062x", i);
    }
}
