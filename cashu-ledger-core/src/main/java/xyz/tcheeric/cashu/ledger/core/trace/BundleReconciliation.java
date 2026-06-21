package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.Collection;
import java.util.Optional;

/**
 * The outcome of matching the SEND and RECEIVE legs of a single off-mint custody
 * bundle (design §5.4 — voucher/token cross-link). A SEND hands off a bundle of
 * proofs (its outputs) that the recipient redeems as the inputs of a RECEIVE; a
 * reconciled bundle has both legs present with byte-identical proof sets.
 *
 * @param bundleId        the shared {@code bundle_id}
 * @param status          the reconciliation verdict
 * @param sendEventId     the SEND event id, when present
 * @param receiveEventId  the RECEIVE event id, when present
 * @param mismatchReason  why the two legs disagree, for {@link Status#MISMATCH}
 */
public record BundleReconciliation(
        String bundleId,
        Status status,
        Optional<String> sendEventId,
        Optional<String> receiveEventId,
        Optional<String> mismatchReason) {

    /**
     * The reconciliation verdict for a bundle.
     *
     * <ul>
     *   <li>{@link #MATCHED} — both legs present and the proof sets are identical.</li>
     *   <li>{@link #MISMATCH} — both legs present but proofs differ ({@code B1_BUNDLE_MISMATCH}).</li>
     *   <li>{@link #PENDING} — only a SEND, still inside the reconciliation window.</li>
     *   <li>{@link #UNMATCHED_SEND} — a SEND with no RECEIVE past the window.</li>
     *   <li>{@link #DANGLING_RECEIVE} — a RECEIVE with no originating SEND.</li>
     *   <li>{@link #ABSENT} — neither leg is present.</li>
     * </ul>
     */
    public enum Status {
        MATCHED,
        MISMATCH,
        PENDING,
        UNMATCHED_SEND,
        DANGLING_RECEIVE,
        ABSENT
    }

    /** Aggregate counters for the {@code /stats} reconciliation summary. */
    public record Stats(long matched, long mismatch, long pending,
                        long unmatchedSend, long danglingReceive) {

        public static Stats of(Collection<BundleReconciliation> reconciliations) {
            long matched = 0, mismatch = 0, pending = 0, unmatchedSend = 0, danglingReceive = 0;
            for (BundleReconciliation r : reconciliations) {
                switch (r.status()) {
                    case MATCHED -> matched++;
                    case MISMATCH -> mismatch++;
                    case PENDING -> pending++;
                    case UNMATCHED_SEND -> unmatchedSend++;
                    case DANGLING_RECEIVE -> danglingReceive++;
                    case ABSENT -> { }
                }
            }
            return new Stats(matched, mismatch, pending, unmatchedSend, danglingReceive);
        }
    }
}
