package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Unit tests for {@link BundleReconciler}: matching SEND↔RECEIVE legs of an off-mint
 * custody bundle and detecting tampering, unmatched sends, and dangling receives.
 */
class BundleReconcilerTest {

    private static final String BUNDLE = "bundle-1";
    private static final String MINT = "https://mint.imani.casa";
    private static final Instant NOW = Instant.parse("2026-06-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;
    private BundleReconciler reconciler;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        reconciler = new BundleReconciler(store, Duration.ofHours(24), CLOCK);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    /** A SEND whose proofs are redeemed unchanged by the RECEIVE reconciles as MATCHED. */
    @Test
    void shouldReportMatchedWhenRedeemedProofsEqualSentProofs() {
        // Given: SEND outputs identical to RECEIVE inputs
        List<ProofRef> bundle = List.of(proof(1, 8), proof(2, 16));
        send("e-send", NOW.minus(Duration.ofHours(1)), bundle);
        receive("e-recv", NOW, bundle);

        // When: reconciling the bundle
        BundleReconciliation result = reconciler.reconcile(BUNDLE);

        // Then: both legs matched, no anomaly
        assertThat(result.status()).isEqualTo(BundleReconciliation.Status.MATCHED);
        assertThat(result.mismatchReason()).isEmpty();
    }

    /** An extra redeemed proof not present in the send is a count mismatch. */
    @Test
    void shouldReportMismatchWhenReceiveCarriesExtraProof() {
        // Given: RECEIVE redeems one more proof than was sent
        send("e-send", NOW.minus(Duration.ofHours(1)), List.of(proof(1, 8)));
        receive("e-recv", NOW, List.of(proof(1, 8), proof(2, 16)));

        // When: reconciling
        BundleReconciliation result = reconciler.reconcile(BUNDLE);

        // Then: flagged as mismatch with a count reason
        assertThat(result.status()).isEqualTo(BundleReconciliation.Status.MISMATCH);
        assertThat(result.mismatchReason()).contains("proof_count_mismatch");
    }

    /** Redeeming the same proofs in a different order is a mismatch (order matters). */
    @Test
    void shouldReportMismatchWhenRedeemedProofsAreReordered() {
        // Given: same proofs, reversed order on redemption
        send("e-send", NOW.minus(Duration.ofHours(1)), List.of(proof(1, 8), proof(2, 16)));
        receive("e-recv", NOW, List.of(proof(2, 16), proof(1, 8)));

        // When: reconciling
        BundleReconciliation result = reconciler.reconcile(BUNDLE);

        // Then: positional tuple mismatch
        assertThat(result.status()).isEqualTo(BundleReconciliation.Status.MISMATCH);
        assertThat(result.mismatchReason()).contains("proof_tuple_mismatch_at_0");
    }

    /** A redeemed proof whose secret differs from the sent proof is a mismatch. */
    @Test
    void shouldReportMismatchWhenRedeemedProofSecretDiffers() {
        // Given: same y/amount but tampered secret on redemption
        send("e-send", NOW.minus(Duration.ofHours(1)),
                List.of(new ProofRef(8, "ks", y(1), Optional.of("secret-a"),
                        Optional.of("c"), Optional.empty(), Optional.empty())));
        receive("e-recv", NOW,
                List.of(new ProofRef(8, "ks", y(1), Optional.of("secret-b"),
                        Optional.of("c"), Optional.empty(), Optional.empty())));

        // When: reconciling
        BundleReconciliation result = reconciler.reconcile(BUNDLE);

        // Then: positional tuple mismatch on the tampered proof
        assertThat(result.status()).isEqualTo(BundleReconciliation.Status.MISMATCH);
        assertThat(result.mismatchReason()).contains("proof_tuple_mismatch_at_0");
    }

    /** A SEND with no RECEIVE past the reconciliation window is an unmatched send. */
    @Test
    void shouldReportUnmatchedSendWhenWindowElapsedWithoutReceive() {
        // Given: a SEND older than the 24h window, no RECEIVE
        send("e-send", NOW.minus(Duration.ofHours(30)), List.of(proof(1, 8)));

        // When: reconciling
        BundleReconciliation result = reconciler.reconcile(BUNDLE);

        // Then: unmatched send
        assertThat(result.status()).isEqualTo(BundleReconciliation.Status.UNMATCHED_SEND);
    }

    /** A recent SEND still inside the window is pending, not yet unmatched. */
    @Test
    void shouldReportPendingWhenSendIsWithinWindow() {
        // Given: a SEND within the last hour, no RECEIVE
        send("e-send", NOW.minus(Duration.ofHours(1)), List.of(proof(1, 8)));

        // When: reconciling
        BundleReconciliation result = reconciler.reconcile(BUNDLE);

        // Then: pending
        assertThat(result.status()).isEqualTo(BundleReconciliation.Status.PENDING);
    }

    /** A RECEIVE with no originating SEND is dangling. */
    @Test
    void shouldReportDanglingReceiveWhenNoSendExists() {
        // Given: only a RECEIVE
        receive("e-recv", NOW, List.of(proof(1, 8)));

        // When: reconciling
        BundleReconciliation result = reconciler.reconcile(BUNDLE);

        // Then: dangling receive
        assertThat(result.status()).isEqualTo(BundleReconciliation.Status.DANGLING_RECEIVE);
    }

    private void send(String eventId, Instant at, List<ProofRef> outputs) {
        store.store(StoredEvent.of(event(eventId, OperationKind.SEND, at, List.of(), outputs)));
    }

    private void receive(String eventId, Instant at, List<ProofRef> inputs) {
        store.store(StoredEvent.of(event(eventId, OperationKind.RECEIVE, at, inputs, List.of())));
    }

    private static TransactionEvent event(String eventId, OperationKind kind, Instant at,
                                          List<ProofRef> inputs, List<ProofRef> outputs) {
        return new TransactionEvent(
                Optional.of(eventId), "op-" + eventId, kind, MINT, "sat",
                at, at, "pk", Optional.empty(), inputs, outputs, List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(BUNDLE), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(eventId), 9079, Optional.empty(),
                        Optional.empty(), at));
    }

    private static ProofRef proof(int i, long amount) {
        return new ProofRef(amount, "ks", y(i), Optional.of("s" + i),
                Optional.of("c" + i), Optional.empty(), Optional.empty());
    }

    private static String y(int i) {
        return "02" + String.format("%062x", i);
    }
}
