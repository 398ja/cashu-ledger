package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Unit tests for {@link QuoteStatusService}: synthesising pending/succeeded/failed/expired from
 * a quote's events, and the expiry sweep flipping lapsed quotes to terminal.
 */
class QuoteStatusServiceTest {

    private static final String MINT = "https://mint.imani.casa";
    private static final String QUOTE = "q-0001";
    private static final Instant NOW = Instant.parse("2026-06-20T12:10:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-06-20T12:05:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;
    private QuoteStatusService service;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        service = new QuoteStatusService(store, index, CLOCK);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    /** A quote with only an unexpired request is pending. */
    @Test
    void shouldReportPendingForUnexpiredOpenQuote() {
        // Given: an open mint quote expiring in the future
        store(quoteRequest("e-q", OperationKind.MINT_QUOTE_REQUESTED, NOW.plusSeconds(300)));

        // When: querying status
        QuoteStatusView view = service.status(MINT, QUOTE).orElseThrow();

        // Then: pending mint
        assertThat(view.status()).isEqualTo("pending");
        assertThat(view.operation()).isEqualTo("mint");
    }

    /** A settling MINT marks the quote succeeded with the settling event id. */
    @Test
    void shouldReportSucceededWhenSettled() {
        // Given: an open quote and a MINT referencing it
        store(quoteRequest("e-q", OperationKind.MINT_QUOTE_REQUESTED, NOW.plusSeconds(300)));
        store(settlement("e-mint", OperationKind.MINT, null));

        // When: querying status
        QuoteStatusView view = service.status(MINT, QUOTE).orElseThrow();

        // Then: succeeded
        assertThat(view.status()).isEqualTo("succeeded");
        assertThat(view.settledEventId()).isEqualTo("e-mint");
    }

    /** A *_FAILED event marks the quote failed and surfaces the reason. */
    @Test
    void shouldReportFailedWithReason() {
        // Given: an open quote and a MINT_FAILED with an error
        store(quoteRequest("e-q", OperationKind.MINT_QUOTE_REQUESTED, NOW.plusSeconds(300)));
        store(settlement("e-fail", OperationKind.MINT_FAILED, "LIGHTNING_FAILURE"));

        // When: querying status
        QuoteStatusView view = service.status(MINT, QUOTE).orElseThrow();

        // Then: failed with reason
        assertThat(view.status()).isEqualTo("failed");
        assertThat(view.failureReason()).isEqualTo("LIGHTNING_FAILURE");
    }

    /** A quote whose expiry has passed with no settlement is expired, and the sweep terminalises it. */
    @Test
    void shouldReportExpiredAndSweepTerminalisesIt() {
        // Given: an open quote already past its expiry
        store(quoteRequest("e-q", OperationKind.MELT_QUOTE_REQUESTED, EXPIRES));

        // When/Then: status is expired
        assertThat(service.status(MINT, QUOTE).orElseThrow().status()).isEqualTo("expired");

        // When: the sweep runs
        int swept = service.sweepExpired(100);

        // Then: it flips the quote event to terminal quote_expired
        assertThat(swept).isEqualTo(1);
        assertThat(index.activityOf("e-q")).get()
                .extracting(SqliteSidecarIndex.ActivityState::reason)
                .isEqualTo(Optional.of("quote_expired"));
    }

    /** An unknown quote yields no status. */
    @Test
    void shouldReturnEmptyForUnknownQuote() {
        assertThat(service.status(MINT, "missing")).isEmpty();
    }

    private void store(TransactionEvent event) {
        store.store(StoredEvent.of(event));
    }

    private static TransactionEvent quoteRequest(String id, OperationKind kind, Instant expiresAt) {
        LightningRef lightning = new LightningRef(QUOTE, MINT, Optional.empty(), Optional.empty(),
                Optional.of(1000L), Optional.of(expiresAt), kind, false);
        return event(id, kind, Optional.of(lightning), null);
    }

    private static TransactionEvent settlement(String id, OperationKind kind, String errorCode) {
        LightningRef lightning = new LightningRef(QUOTE, MINT, Optional.empty(), Optional.empty(),
                Optional.of(1000L), Optional.empty(),
                kind.name().startsWith("MELT") ? OperationKind.MELT_QUOTE_REQUESTED
                        : OperationKind.MINT_QUOTE_REQUESTED, false);
        return event(id, kind, Optional.of(lightning), errorCode);
    }

    private static TransactionEvent event(String id, OperationKind kind, Optional<LightningRef> lightning,
                                          String errorCode) {
        boolean hasOutputs = kind == OperationKind.MINT;
        return new TransactionEvent(
                Optional.of(id), "op-" + id, kind, MINT, "sat", NOW, NOW, "pk", Optional.empty(),
                List.of(), hasOutputs ? List.of(new ProofRef(1000, "ks", "02ab", Optional.of("s"),
                        Optional.of("c"), Optional.empty(), Optional.empty())) : List.of(),
                List.of(), lightning, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(errorCode),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(id), 9079, Optional.empty(), Optional.empty(), NOW));
    }
}
