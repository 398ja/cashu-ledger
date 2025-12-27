package xyz.tcheeric.cashu.ledger.core.state;

import xyz.tcheeric.cashu.ledger.core.model.TransitionActor;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStateMetadata;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single state transition event for a voucher.
 */
public record VoucherStateTransition(
        String voucherId,
        VoucherStatus fromStatus,
        VoucherStatus toStatus,
        long stateVersion,
        Instant transitionAt,
        TransitionActor transitionActor,
        String transitionReason,
        Instant expiresAt,
        List<String> splitInto,
        String claimedBy,
        Instant claimedAt,
        String redeemedBy,
        Instant redeemedAt,
        String reclaimedBy,
        Instant reclaimedAt,
        Instant createdAt,
        String eventId,
        String relay,
        Instant issuedAt
) {

    public VoucherStateTransition {
        voucherId = Objects.requireNonNull(voucherId, "voucherId");
        toStatus = toStatus != null ? toStatus : VoucherStatus.UNKNOWN;
        fromStatus = fromStatus != null ? fromStatus : VoucherStatus.UNKNOWN;
        transitionActor = transitionActor != null ? transitionActor : TransitionActor.UNKNOWN;
        splitInto = splitInto == null ? List.of() : List.copyOf(splitInto);
    }

    public boolean isTerminal() {
        return toStatus.isTerminal();
    }

    public static VoucherStateTransition fromNode(VoucherNode node) {
        Objects.requireNonNull(node, "node");
        VoucherStateMetadata metadata = node.stateMetadata() != null
                ? node.stateMetadata()
                : VoucherStateMetadata.empty();
        return new VoucherStateTransition(
                node.voucherId(),
                metadata.previousStatus(),
                node.status(),
                metadata.stateVersion(),
                metadata.transitionAt() != null ? metadata.transitionAt() : node.issuedAt(),
                metadata.transitionActor(),
                metadata.transitionReason(),
                node.expiresAt(),
                metadata.splitInto(),
                metadata.claimedBy(),
                metadata.claimedAt(),
                metadata.redeemedBy(),
                metadata.redeemedAt(),
                metadata.reclaimedBy(),
                metadata.reclaimedAt(),
                node.eventMetadata() != null ? node.eventMetadata().createdAt() : node.issuedAt(),
                node.eventMetadata() != null ? node.eventMetadata().eventId() : null,
                node.eventMetadata() != null ? node.eventMetadata().relay() : null,
                node.issuedAt()
        );
    }
}
