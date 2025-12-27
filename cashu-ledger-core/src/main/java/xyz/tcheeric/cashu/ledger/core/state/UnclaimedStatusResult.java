package xyz.tcheeric.cashu.ledger.core.state;

/**
 * Result of checking whether an unclaimed voucher can be reclaimed.
 */
public record UnclaimedStatusResult(
        String voucherId,
        boolean reclaimable,
        String message
) {
    public static UnclaimedStatusResult notSupported(String voucherId) {
        return new UnclaimedStatusResult(
                voucherId,
                false,
                "Proof status checking requires mint connectivity. Suggestion: run with mint access to verify proofs."
        );
    }
}
