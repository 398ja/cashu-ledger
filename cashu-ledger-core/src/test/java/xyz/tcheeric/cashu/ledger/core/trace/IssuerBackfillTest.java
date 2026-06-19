package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Unit tests for {@link IssuerBackfill}: attributing an issuer to events published before
 * their voucher bound, without overwriting issuer values that came from the event itself.
 */
class IssuerBackfillTest {

    private static final String MINT = "https://mint.imani.casa";
    private static final Instant FUTURE = Instant.ofEpochSecond(4_000_000_000L);

    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;
    private IssuerBackfill backfill;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        backfill = new IssuerBackfill(index);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    /** An issuer-less event gains the voucher's issuer and is marked back-filled. */
    @Test
    void shouldBackfillIssuerForPreviouslyUnboundEvent() {
        // Given: a MINT with no issuer producing proof y0
        store(event("e-mint", null, List.of(proof(0))));

        // When: the voucher arrives and back-fills the issuer for that proof
        int rows = backfill.backfill("v1", "iss-1", "pk-1", List.of(identity(0)));

        // Then: the event is attributed to the issuer and flagged as back-filled
        assertThat(rows).isEqualTo(1);
        assertThat(store.findByIssuerId("iss-1", Instant.EPOCH, FUTURE, 10))
                .extracting(e -> e.event().eventId().orElseThrow()).containsExactly("e-mint");
        assertThat(index.isIssuerBackfilled("e-mint")).isTrue();
    }

    /** An event that already carries an issuer tag is never overwritten by back-fill. */
    @Test
    void shouldNotOverwriteIssuerThatCameFromTheEvent() {
        // Given: a MINT that already declares issuer iss-orig
        store(event("e-mint", "iss-orig", List.of(proof(0))));

        // When: a back-fill proposes a different issuer
        backfill.backfill("v1", "iss-other", "pk-other", List.of(identity(0)));

        // Then: the original issuer survives and provenance stays event_tag
        assertThat(store.findByIssuerId("iss-orig", Instant.EPOCH, FUTURE, 10)).hasSize(1);
        assertThat(store.findByIssuerId("iss-other", Instant.EPOCH, FUTURE, 10)).isEmpty();
        assertThat(index.isIssuerBackfilled("e-mint")).isFalse();
    }

    /** Re-binding a proof to a new issuer rebinds the back-filled event. */
    @Test
    void shouldRebindBackfilledEventOnIssuerOverwrite() {
        // Given: an issuer-less event already back-filled to iss-1
        store(event("e-mint", null, List.of(proof(0))));
        backfill.backfill("v1", "iss-1", "pk-1", List.of(identity(0)));

        // When: a corrected voucher rebinds the proof to iss-2
        backfill.backfill("v1", "iss-2", "pk-2", List.of(identity(0)));

        // Then: the event follows the new issuer
        assertThat(store.findByIssuerId("iss-2", Instant.EPOCH, FUTURE, 10)).hasSize(1);
        assertThat(store.findByIssuerId("iss-1", Instant.EPOCH, FUTURE, 10)).isEmpty();
    }

    private void store(TransactionEvent event) {
        store.store(StoredEvent.of(event));
    }

    private static ProofIdentity identity(int i) {
        return new ProofIdentity(MINT, "ks", y(i));
    }

    private static TransactionEvent event(String eventId, String issuerId, List<ProofRef> outputs) {
        return new TransactionEvent(
                Optional.of(eventId), "op-" + eventId, OperationKind.MINT, MINT, "sat",
                Instant.EPOCH, Instant.EPOCH, "pk", Optional.empty(), List.of(), outputs, List.of(),
                Optional.empty(), Optional.empty(), Optional.ofNullable(issuerId), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
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
