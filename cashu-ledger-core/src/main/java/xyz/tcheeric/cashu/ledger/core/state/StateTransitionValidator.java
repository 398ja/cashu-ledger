package xyz.tcheeric.cashu.ledger.core.state;

import xyz.tcheeric.cashu.ledger.core.model.TransitionActor;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Validates voucher state transitions against the allowed state machine.
 */
public class StateTransitionValidator {

    public StateValidationResult validate(VoucherStateTransition transition, VoucherStateSnapshot currentSnapshot) {
        Objects.requireNonNull(transition, "transition");

        VoucherStatus toStatus = transition.toStatus();
        VoucherStatus fromStatus = resolveFromStatus(transition, currentSnapshot);

        long expectedVersion = currentSnapshot == null ? 0L : currentSnapshot.stateVersion() + 1;
        if (transition.stateVersion() != expectedVersion) {
            return reject(
                    "State transition rejected",
                    "State version " + transition.stateVersion() + " does not match expected " + expectedVersion,
                    "Refresh ledger history and publish with the next state_version"
            );
        }

        if (currentSnapshot != null && currentSnapshot.isTerminal()) {
            if (!toStatus.equals(currentSnapshot.status())) {
                return reject(
                        "Terminal transition rejected",
                        "Voucher is already in terminal status " + currentSnapshot.status(),
                        "Use the same terminal status with an incremented state_version if replaying the event"
                );
            }
        }

        return switch (toStatus) {
            case CLAIMED -> validateClaim(fromStatus, transition);
            case REDEEMED -> validateRedeemed(fromStatus, transition);
            case SPLIT -> validateSplit(fromStatus, transition);
            case RECLAIMED -> validateReclaimed(fromStatus, transition);
            case EXPIRED -> validateExpired(currentSnapshot, transition);
            case REVOKED -> validateRevoked(currentSnapshot, transition);
            case ISSUED, UNKNOWN -> StateValidationResult.success();
        };
    }

    private StateValidationResult validateClaim(VoucherStatus fromStatus, VoucherStateTransition transition) {
        if (fromStatus != VoucherStatus.ISSUED) {
            return reject(
                    "Claim transition rejected",
                    "Voucher is not in ISSUED state",
                    "Refresh ledger history and ensure the previous status is ISSUED before publishing a claim"
            );
        }
        if (transition.claimedBy() == null || transition.claimedBy().isBlank()) {
            return reject(
                    "Claim transition rejected",
                    "Claimed-by actor is missing",
                    "Populate claimed_by with the recipient pubkey and retry"
            );
        }
        if (transition.claimedAt() == null) {
            return reject(
                    "Claim transition rejected",
                    "Claim timestamp is missing",
                    "Set claimed_at to the mint swap completion time before publishing"
            );
        }
        if (transition.issuedAt() != null && transition.claimedAt().isBefore(transition.issuedAt())) {
            return reject(
                    "Claim transition rejected",
                    "Claim timestamp precedes issuance",
                    "Ensure claimed_at is after issued_at and republish"
            );
        }
        return StateValidationResult.success();
    }

    private StateValidationResult validateRedeemed(VoucherStatus fromStatus, VoucherStateTransition transition) {
        if (fromStatus != VoucherStatus.CLAIMED) {
            return reject(
                    "Redemption transition rejected",
                    "Voucher is not CLAIMED",
                    "Reload ledger history and publish redemption only after CLAIMED"
            );
        }
        if (transition.redeemedAt() == null) {
            return reject(
                    "Redemption transition rejected",
                    "Redemption timestamp is missing",
                    "Provide redeemed_at from the settlement event before publishing"
            );
        }
        if (transition.transitionActor() != TransitionActor.RECIPIENT
                && transition.transitionActor() != TransitionActor.ISSUER) {
            return reject(
                    "Redemption transition rejected",
                    "Transition actor must be recipient or issuer",
                    "Set transition_actor to recipient or issuer depending on who settled the voucher"
            );
        }
        return StateValidationResult.success();
    }

    private StateValidationResult validateSplit(VoucherStatus fromStatus, VoucherStateTransition transition) {
        if (fromStatus != VoucherStatus.CLAIMED) {
            return reject(
                    "Split transition rejected",
                    "Voucher is not CLAIMED",
                    "Only split vouchers that are claimed; publish a claimed event first"
            );
        }
        if (transition.splitInto().isEmpty()) {
            return reject(
                    "Split transition rejected",
                    "Child vouchers are missing",
                    "Provide split_into child voucher IDs before publishing the split"
            );
        }
        if (transition.transitionAt() == null) {
            return reject(
                    "Split transition rejected",
                    "Split timestamp is missing",
                    "Set split_at/transition_at to the split execution time and retry"
            );
        }
        return StateValidationResult.success();
    }

    private StateValidationResult validateReclaimed(VoucherStatus fromStatus, VoucherStateTransition transition) {
        if (fromStatus != VoucherStatus.ISSUED) {
            return reject(
                    "Reclaim transition rejected",
                    "Voucher is not ISSUED",
                    "Confirm the voucher is unclaimed before attempting reclaim"
            );
        }
        if (transition.reclaimedBy() == null || transition.reclaimedBy().isBlank()) {
            return reject(
                    "Reclaim transition rejected",
                    "Reclaimed-by actor is missing",
                    "Set reclaimed_by to the sender pubkey before publishing"
            );
        }
        if (transition.transitionActor() != TransitionActor.SENDER) {
            return reject(
                    "Reclaim transition rejected",
                    "Transition actor must be sender",
                    "Mark transition_actor as sender to indicate who reclaimed the voucher"
            );
        }
        return StateValidationResult.success();
    }

    private StateValidationResult validateExpired(VoucherStateSnapshot currentSnapshot, VoucherStateTransition transition) {
        if (currentSnapshot != null && currentSnapshot.isTerminal()) {
            return reject(
                    "Expiry transition rejected",
                    "Voucher is already terminal in state " + currentSnapshot.status(),
                    "Skip expiry or replay the same terminal status with the next state_version"
            );
        }
        if (transition.expiresAt() == null) {
            return reject(
                    "Expiry transition rejected",
                    "expires_at tag is missing",
                    "Populate expires_at and transition_at to document expiry timing"
            );
        }
        if (transition.transitionAt() == null) {
            return reject(
                    "Expiry transition rejected",
                    "Transition timestamp is missing",
                    "Set transition_at to the expiry time and republish"
            );
        }
        if (transition.transitionAt().isBefore(transition.expiresAt())) {
            return reject(
                    "Expiry transition rejected",
                    "Transition occurred before expiry timestamp",
                    "Align transition_at to the expiry moment or adjust expires_at accordingly"
            );
        }
        return StateValidationResult.success();
    }

    private StateValidationResult validateRevoked(VoucherStateSnapshot currentSnapshot, VoucherStateTransition transition) {
        if (currentSnapshot != null && currentSnapshot.isTerminal()) {
            return reject(
                    "Revocation transition rejected",
                    "Voucher is already terminal in state " + currentSnapshot.status(),
                    "Publish revocation with a higher state_version only if replacing the same terminal status"
            );
        }
        if (transition.transitionActor() != TransitionActor.ISSUER) {
            return reject(
                    "Revocation transition rejected",
                    "Transition actor is not issuer",
                    "Set transition_actor to issuer to record who revoked the voucher"
            );
        }
        if (transition.transitionReason() == null || transition.transitionReason().isBlank()) {
            return reject(
                    "Revocation transition rejected",
                    "transition_reason is missing",
                    "Provide a short revocation reason such as duplicate_send or fraud"
            );
        }
        return StateValidationResult.success();
    }

    private VoucherStatus resolveFromStatus(VoucherStateTransition transition, VoucherStateSnapshot currentSnapshot) {
        if (transition.fromStatus() != null && transition.fromStatus() != VoucherStatus.UNKNOWN) {
            return transition.fromStatus();
        }
        if (currentSnapshot != null) {
            return currentSnapshot.status();
        }
        return VoucherStatus.UNKNOWN;
    }

    private StateValidationResult reject(String what, String why, String suggestion) {
        return StateValidationResult.failure(formatMessage(what, why, suggestion));
    }

    private String formatMessage(String what, String why, String suggestion) {
        return what + ". " + why + ". Suggestion: " + suggestion + ".";
    }
}
