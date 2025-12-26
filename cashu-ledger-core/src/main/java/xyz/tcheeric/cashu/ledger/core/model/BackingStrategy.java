package xyz.tcheeric.cashu.ledger.core.model;

/**
 * Voucher backing strategy.
 */
public enum BackingStrategy {
    PROPORTIONAL,
    FIXED,
    UNKNOWN;

    public static BackingStrategy fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return BackingStrategy.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
