package xyz.tcheeric.cashu.ledger.core.state;

import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains the latest state snapshot per voucher, applying validation and conflict resolution.
 */
public class VoucherStateJournal {

    private final StateTransitionValidator validator;
    private final Map<String, VoucherStateSnapshot> snapshots;

    public VoucherStateJournal() {
        this(new StateTransitionValidator(), new ConcurrentHashMap<>());
    }

    public VoucherStateJournal(StateTransitionValidator validator, Map<String, VoucherStateSnapshot> snapshots) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    public StateDecision record(VoucherNode node) {
        Objects.requireNonNull(node, "node");
        VoucherStateTransition transition = VoucherStateTransition.fromNode(node);
        VoucherStateSnapshot current = snapshots.get(transition.voucherId());

        StateValidationResult validation = validator.validate(transition, current);
        if (!validation.valid()) {
            return StateDecision.rejectedInvalid(validation.message(), current, transition);
        }

        VoucherStateSnapshot candidate = VoucherStateSnapshot.fromNode(node);
        if (current != null && !shouldReplace(current, candidate, transition)) {
            return StateDecision.rejectedStale(
                    formatMessage(
                            "State change discarded",
                            "Existing state_version " + current.stateVersion() + " is preferred over " + candidate.stateVersion(),
                            "Refresh relay data and republish with a higher state_version if an update is required"
                    ),
                    current,
                    transition
            );
        }

        snapshots.put(candidate.voucherId(), candidate);
        return StateDecision.accepted(candidate, transition);
    }

    public Optional<VoucherStateSnapshot> snapshot(String voucherId) {
        return Optional.ofNullable(snapshots.get(voucherId));
    }

    private boolean shouldReplace(VoucherStateSnapshot current, VoucherStateSnapshot candidate, VoucherStateTransition transition) {
        if (candidate.stateVersion() > current.stateVersion()) {
            return true;
        }
        if (candidate.stateVersion() < current.stateVersion()) {
            return false;
        }

        if (current.status().isTerminal() && transition.toStatus() != current.status()) {
            return false;
        }

        if (isAfter(candidate.transitionAt(), current.transitionAt())) {
            return true;
        }
        if (isAfter(current.transitionAt(), candidate.transitionAt())) {
            return false;
        }

        if (isAfter(candidate.createdAt(), current.createdAt())) {
            return true;
        }
        if (isAfter(current.createdAt(), candidate.createdAt())) {
            return false;
        }

        if (candidate.eventId() != null && current.eventId() != null) {
            return candidate.eventId().compareTo(current.eventId()) > 0;
        }
        // If we reach here, at least one eventId is null. Prefer the one with a non-null eventId.
        return candidate.eventId() != null;
    }

    private boolean isAfter(Instant left, Instant right) {
        if (left == null) {
            return false;
        }
        if (right == null) {
            return true;
        }
        return left.isAfter(right);
    }

    private String formatMessage(String what, String why, String suggestion) {
        return what + ". " + why + ". Suggestion: " + suggestion + ".";
    }

    public record StateDecision(StateDecisionType decision, VoucherStateSnapshot snapshot, String message, VoucherStateTransition transition) {
        public static StateDecision accepted(VoucherStateSnapshot snapshot, VoucherStateTransition transition) {
            return new StateDecision(StateDecisionType.ACCEPTED, snapshot, "", transition);
        }

        public static StateDecision rejectedInvalid(String message, VoucherStateSnapshot snapshot, VoucherStateTransition transition) {
            return new StateDecision(StateDecisionType.REJECTED_INVALID, snapshot, message, transition);
        }

        public static StateDecision rejectedStale(String message, VoucherStateSnapshot snapshot, VoucherStateTransition transition) {
            return new StateDecision(StateDecisionType.REJECTED_STALE, snapshot, message, transition);
        }
    }

    public enum StateDecisionType {
        ACCEPTED,
        REJECTED_INVALID,
        REJECTED_STALE
    }
}
