package xyz.tcheeric.cashu.ledger.core.trace;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Reconciles the two legs of an off-mint custody handoff. A SEND publishes a bundle
 * of proofs (its outputs) under a {@code bundle_id}; the recipient redeems exactly
 * those proofs as the inputs of a RECEIVE sharing that id. This reconciler matches
 * the legs and reports anomalies: a bundle whose redeemed proofs are not byte-equal
 * to the sent proofs is flagged {@link #MISMATCH_CODE}; a SEND with no RECEIVE past
 * the reconciliation window is an unmatched send; a RECEIVE with no SEND is dangling
 * (design §5.4, spec Edge Cases — unmatched send/dangling receive).
 */
public final class BundleReconciler {

    /** Error code surfaced when a redeemed bundle does not equal what was sent. */
    public static final String MISMATCH_CODE = "B1_BUNDLE_MISMATCH";

    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
    private static final int BUNDLE_LIMIT = 64;

    private final TraceEventStore store;
    private final Duration reconciliationWindow;
    private final Clock clock;

    public BundleReconciler(TraceEventStore store) {
        this(store, DEFAULT_WINDOW, Clock.systemUTC());
    }

    public BundleReconciler(TraceEventStore store, Duration reconciliationWindow, Clock clock) {
        this.store = store;
        this.reconciliationWindow = reconciliationWindow;
        this.clock = clock;
    }

    /** Reconciles the SEND and RECEIVE legs sharing the given {@code bundleId}. */
    public BundleReconciliation reconcile(String bundleId) {
        List<StoredEvent> events = store.findByBundleId(bundleId, BUNDLE_LIMIT);
        Optional<StoredEvent> send = firstOfKind(events, OperationKind.SEND);
        Optional<StoredEvent> receive = firstOfKind(events, OperationKind.RECEIVE);

        if (send.isPresent() && receive.isPresent()) {
            return bothLegs(bundleId, send.get(), receive.get());
        }
        if (send.isPresent()) {
            return sendOnly(bundleId, send.get());
        }
        if (receive.isPresent()) {
            return new BundleReconciliation(bundleId, BundleReconciliation.Status.DANGLING_RECEIVE,
                    Optional.empty(), eventId(receive.get()), Optional.empty());
        }
        return new BundleReconciliation(bundleId, BundleReconciliation.Status.ABSENT,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private BundleReconciliation bothLegs(String bundleId, StoredEvent send, StoredEvent receive) {
        Optional<String> reason = mismatchReason(send.event(), receive.event());
        BundleReconciliation.Status status = reason.isPresent()
                ? BundleReconciliation.Status.MISMATCH : BundleReconciliation.Status.MATCHED;
        return new BundleReconciliation(bundleId, status, eventId(send), eventId(receive), reason);
    }

    private BundleReconciliation sendOnly(String bundleId, StoredEvent send) {
        boolean past = send.event().transitionAt()
                .isBefore(Instant.now(clock).minus(reconciliationWindow));
        BundleReconciliation.Status status = past
                ? BundleReconciliation.Status.UNMATCHED_SEND : BundleReconciliation.Status.PENDING;
        return new BundleReconciliation(bundleId, status, eventId(send), Optional.empty(), Optional.empty());
    }

    private static Optional<String> mismatchReason(TransactionEvent send, TransactionEvent receive) {
        if (!send.mintUrl().equals(receive.mintUrl())) {
            return Optional.of("mint_url_mismatch");
        }
        if (!send.unit().equals(receive.unit())) {
            return Optional.of("unit_mismatch");
        }
        List<ProofRef> sent = send.outputs();
        List<ProofRef> redeemed = receive.inputs();
        if (sent.size() != redeemed.size()) {
            return Optional.of("proof_count_mismatch");
        }
        for (int i = 0; i < sent.size(); i++) {
            if (!sameProof(sent.get(i), redeemed.get(i))) {
                return Optional.of("proof_tuple_mismatch_at_" + i);
            }
        }
        return Optional.empty();
    }

    private static boolean sameProof(ProofRef sent, ProofRef redeemed) {
        return sent.keysetId().equals(redeemed.keysetId())
                && sent.amount() == redeemed.amount()
                && sent.y().equals(redeemed.y())
                && sent.secret().equals(redeemed.secret())
                && sent.c().equals(redeemed.c());
    }

    private static Optional<StoredEvent> firstOfKind(List<StoredEvent> events, OperationKind kind) {
        return events.stream().filter(e -> e.event().kind() == kind).findFirst();
    }

    private static Optional<String> eventId(StoredEvent event) {
        return event.event().eventId();
    }
}
