package xyz.tcheeric.cashu.ledger.trace.core;

import java.util.Locale;

/**
 * Per-output classification carried by the {@code output_role} tag, distinguishing
 * intended outputs from change and overpaid-fee returns (design §5.3, NUT-08).
 * When present, the count must match the number of outputs.
 */
public enum OutputRole {

    TARGET,
    CHANGE,
    FEE_RETURN;

    /** The lowercase wire form (e.g. {@code "fee_return"}). */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses the lowercase {@code output_role} tag value.
     *
     * @throws IllegalArgumentException if the value is not a recognised role
     */
    public static OutputRole fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("output role value must not be null");
        }
        return OutputRole.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
