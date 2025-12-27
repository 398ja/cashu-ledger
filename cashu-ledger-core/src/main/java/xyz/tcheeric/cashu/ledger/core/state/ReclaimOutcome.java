package xyz.tcheeric.cashu.ledger.core.state;

/**
 * Outcome of attempting to reclaim an unclaimed voucher.
 */
public record ReclaimOutcome(
        String voucherId,
        boolean success,
        String newVoucherId,
        String message
) {
    public static ReclaimOutcome notSupported(String voucherId) {
        return new ReclaimOutcome(
                voucherId,
                false,
                null,
                "Reclaim requires mint connectivity and token proofs. Suggestion: retry with mint access and token file."
        );
    }
}
