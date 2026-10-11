package xyz.tcheeric.cashu.ledger.core.state;

import java.time.Instant;
import xyz.tcheeric.cashu.ledger.core.model.TransitionActor;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;

/** Describes a single status change for history output. */
public record StatusChange(
    String voucherId,
    VoucherStatus status,
    VoucherStatus previousStatus,
    long stateVersion,
    Instant transitionAt,
    Instant createdAt,
    String relay,
    String eventId,
    TransitionActor transitionActor) {}
