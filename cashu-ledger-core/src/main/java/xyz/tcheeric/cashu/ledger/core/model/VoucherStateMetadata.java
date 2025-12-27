package xyz.tcheeric.cashu.ledger.core.model;

import java.time.Instant;
import java.util.List;

/**
 * Transition-specific metadata describing how a voucher entered its current state.
 */
public record VoucherStateMetadata(
        VoucherStatus previousStatus,
        long stateVersion,
        Instant transitionAt,
        TransitionActor transitionActor,
        String transitionReason,
        String claimedBy,
        Instant claimedAt,
        String redeemedBy,
        Instant redeemedAt,
        String reclaimedBy,
        Instant reclaimedAt,
        List<String> splitInto
) {
    public VoucherStateMetadata {
        splitInto = splitInto == null ? List.of() : List.copyOf(splitInto);
    }

    public static VoucherStateMetadata empty() {
        return new VoucherStateMetadata(
                VoucherStatus.UNKNOWN,
                0L,
                null,
                TransitionActor.UNKNOWN,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of()
        );
    }
}
