package xyz.tcheeric.cashu.ledger.web.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.core.trace.IndexedTraceEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.InMemoryRawEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.ProofIdentity;
import xyz.tcheeric.cashu.ledger.core.trace.IssuerBackfill;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.web.security.TraceAuthority;
import xyz.tcheeric.cashu.ledger.web.security.TraceIssuerProperties;
import xyz.tcheeric.cashu.ledger.web.security.TraceIssuerProperties.PreVoucherExposure;
import xyz.tcheeric.cashu.ledger.web.security.TracePrincipal;

/**
 * Unit tests for {@link TraceResponseMapper} issuer exposure: the pre-voucher exposure
 * control and the issuerProvenance annotation (design §5.3.1).
 */
class TraceResponseMapperTest {

    private static final String MINT = "https://mint.imani.casa";

    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    /** A voucher-bound event exposes its issuer to a summary reader, tagged event_tag. */
    @Test
    void shouldExposeIssuerForVoucherBoundEventToSummaryReader() {
        // Given: an event carrying both a voucher_ref and an issuer
        StoredEvent event = stored(event("e1", "v1", "iss-1"));
        TraceResponseMapper mapper = mapper(PreVoucherExposure.SUMMARY_WITHHOLD);

        // When: a summary-only reader views it
        EventView view = mapper.toView(event, summaryReader());

        // Then: issuer returned with event_tag provenance
        assertThat(view.issuerId()).isEqualTo("iss-1");
        assertThat(view.issuerProvenance()).isEqualTo("event_tag");
    }

    /** With summary-withhold, an unbound event hides its issuer from a summary reader. */
    @Test
    void shouldWithholdIssuerFromSummaryReaderForUnboundEvent() {
        // Given: an issuer-bearing event with no voucher binding
        StoredEvent event = stored(event("e1", null, "iss-1"));
        TraceResponseMapper mapper = mapper(PreVoucherExposure.SUMMARY_WITHHOLD);

        // When: a summary-only reader views it
        EventView view = mapper.toView(event, summaryReader());

        // Then: issuer fields are withheld
        assertThat(view.issuerId()).isNull();
        assertThat(view.issuerProvenance()).isNull();
    }

    /** With summary-withhold, an unbound event still shows its issuer to a full reader. */
    @Test
    void shouldExposeIssuerToFullReaderForUnboundEvent() {
        // Given: an issuer-bearing event with no voucher binding
        StoredEvent event = stored(event("e1", null, "iss-1"));
        TraceResponseMapper mapper = mapper(PreVoucherExposure.SUMMARY_WITHHOLD);

        // When: a full reader views it
        EventView view = mapper.toView(event, fullReader());

        // Then: issuer returned
        assertThat(view.issuerId()).isEqualTo("iss-1");
    }

    /** With suppress mode, an unbound event hides its issuer even from a full reader. */
    @Test
    void shouldSuppressIssuerFromAllReadersForUnboundEvent() {
        // Given: an issuer-bearing event with no voucher binding, suppress mode
        StoredEvent event = stored(event("e1", null, "iss-1"));
        TraceResponseMapper mapper = mapper(PreVoucherExposure.SUPPRESS);

        // When: a full reader views it
        EventView view = mapper.toView(event, fullReader());

        // Then: issuer suppressed
        assertThat(view.issuerId()).isNull();
    }

    /** A back-filled issuer is exposed and annotated index_backfill. */
    @Test
    void shouldAnnotateBackfilledIssuerProvenance() {
        // Given: an issuer-less event whose issuer is later back-filled
        store.store(stored(event("e1", null, null, proof(0))));
        new IssuerBackfill(index).backfill("v1", "iss-1", "pk-1",
                List.of(new ProofIdentity(MINT, "ks", y(0))));
        StoredEvent reloaded = store.findByEventId("e1").orElseThrow();
        TraceResponseMapper mapper = mapper(PreVoucherExposure.SUMMARY_WITHHOLD);

        // When: a summary reader views it (back-fill counts as bound)
        EventView view = mapper.toView(reloaded, summaryReader());

        // Then: issuer exposed with index_backfill provenance
        assertThat(view.issuerId()).isEqualTo("iss-1");
        assertThat(view.issuerProvenance()).isEqualTo("index_backfill");
    }

    private TraceResponseMapper mapper(PreVoucherExposure mode) {
        TraceIssuerProperties props = new TraceIssuerProperties();
        props.setPreVoucherExposure(mode);
        return new TraceResponseMapper(index, props);
    }

    private StoredEvent stored(TransactionEvent event) {
        return StoredEvent.of(event);
    }

    private static TracePrincipal summaryReader() {
        return new TracePrincipal("pk", Set.of(TraceAuthority.READ_SUMMARY));
    }

    private static TracePrincipal fullReader() {
        return new TracePrincipal("pk", Set.of(TraceAuthority.READ_FULL));
    }

    private static TransactionEvent event(String eventId, String voucherRef, String issuerId,
                                          ProofRef... outputs) {
        return new TransactionEvent(
                Optional.of(eventId), "op-" + eventId, OperationKind.MINT, MINT, "sat",
                Instant.EPOCH, Instant.EPOCH, "pk", Optional.empty(), List.of(), List.of(outputs),
                List.of(), Optional.empty(), Optional.ofNullable(voucherRef),
                Optional.ofNullable(issuerId), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
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
