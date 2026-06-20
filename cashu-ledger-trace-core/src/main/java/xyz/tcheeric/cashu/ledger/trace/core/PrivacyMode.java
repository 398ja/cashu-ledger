package xyz.tcheeric.cashu.ledger.trace.core;

import java.util.Locale;

/**
 * The serialisation policy for sensitive proof fields on a transaction event.
 *
 * <ul>
 *   <li>{@link #FULL} — raw {@code secret}, {@code C}, {@code witness}, {@code dleq}.</li>
 *   <li>{@link #HASHED} — {@code secret}/{@code C}/{@code witness} replaced by
 *       HMAC-SHA-256 hex under the deployment redaction key; {@code dleq} omitted.</li>
 *   <li>{@link #MINIMAL} — all secret-bearing fields omitted; only
 *       {@code amount}, {@code keysetId}, {@code y} remain.</li>
 * </ul>
 *
 * In every mode {@code amount}, {@code keysetId}, and {@code y} are present, since
 * {@code y} is the non-reversible graph join key.
 */
public enum PrivacyMode {

    FULL,
    HASHED,
    MINIMAL;

    /** The lowercase wire form used in the {@code privacy_mode} tag. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses the lowercase {@code privacy_mode} tag value back to a mode.
     *
     * @throws IllegalArgumentException if the value is not a recognised mode
     */
    public static PrivacyMode fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("privacy mode value must not be null");
        }
        return PrivacyMode.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
