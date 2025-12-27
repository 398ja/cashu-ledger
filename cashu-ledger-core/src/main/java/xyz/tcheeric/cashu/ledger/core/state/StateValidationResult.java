package xyz.tcheeric.cashu.ledger.core.state;

/**
 * Outcome of a state transition validation.
 */
public record StateValidationResult(boolean valid, String message) {

    public static StateValidationResult success() {
        return new StateValidationResult(true, "");
    }

    public static StateValidationResult failure(String message) {
        return new StateValidationResult(false, message);
    }
}
