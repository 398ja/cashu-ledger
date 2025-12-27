package xyz.tcheeric.cashu.ledger.core.state;

import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStateMetadata;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Snapshot of the latest accepted state for a voucher.
 */
public record VoucherStateSnapshot(
        String voucherId,
        VoucherStatus status,
        VoucherStatus previousStatus,
        long stateVersion,
        Instant transitionAt,
        Instant createdAt,
        String eventId,
        String relay
) {

    public VoucherStateSnapshot {
        voucherId = Objects.requireNonNull(voucherId, "voucherId");
        status = status != null ? status : VoucherStatus.UNKNOWN;
        previousStatus = previousStatus != null ? previousStatus : VoucherStatus.UNKNOWN;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public static VoucherStateSnapshot fromNode(VoucherNode node) {
        Objects.requireNonNull(node, "node");
        VoucherStateMetadata metadata = node.stateMetadata() != null
                ? node.stateMetadata()
                : VoucherStateMetadata.empty();
        return new VoucherStateSnapshot(
                node.voucherId(),
                node.status(),
                metadata.previousStatus(),
                metadata.stateVersion(),
                metadata.transitionAt() != null ? metadata.transitionAt() : node.issuedAt(),
                node.eventMetadata() != null ? node.eventMetadata().createdAt() : node.issuedAt(),
                node.eventMetadata() != null ? node.eventMetadata().eventId() : null,
                node.eventMetadata() != null ? node.eventMetadata().relay() : null
        );
    }
}
