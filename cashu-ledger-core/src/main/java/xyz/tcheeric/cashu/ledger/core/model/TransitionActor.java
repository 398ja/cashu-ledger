package xyz.tcheeric.cashu.ledger.core.model;

/**
 * Actor initiating a voucher state transition.
 */
public enum TransitionActor {
    ISSUER,
    RECIPIENT,
    SENDER,
    SYSTEM,
    UNKNOWN;

    public static TransitionActor fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return TransitionActor.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
