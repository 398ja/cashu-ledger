package xyz.tcheeric.cashu.ledger.web.security;

import java.util.Optional;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;

/**
 * An access level a caller may hold for the trace API (design §7.3). The read
 * levels map to the privacy shape returned to the caller; {@link #ADMIN} gates the
 * admin endpoints.
 */
public enum TraceAuthority {

    READ_SUMMARY,
    READ_HASHED,
    READ_FULL,
    ADMIN;

    /** The privacy shape this read authority grants, if it is a read level. */
    public Optional<PrivacyMode> readMode() {
        return switch (this) {
            case READ_FULL -> Optional.of(PrivacyMode.FULL);
            case READ_HASHED -> Optional.of(PrivacyMode.HASHED);
            case READ_SUMMARY -> Optional.of(PrivacyMode.MINIMAL);
            case ADMIN -> Optional.empty();
        };
    }

    /**
     * Parses a config grant string ({@code trace:read:full}, {@code trace:read:hashed},
     * {@code trace:read:summary}, {@code trace:admin}).
     *
     * @throws IllegalArgumentException if the grant is unrecognised
     */
    public static TraceAuthority fromGrant(String grant) {
        return switch (grant.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "trace:read:full" -> READ_FULL;
            case "trace:read:hashed" -> READ_HASHED;
            case "trace:read:summary" -> READ_SUMMARY;
            case "trace:admin" -> ADMIN;
            default -> throw new IllegalArgumentException("Unknown trace grant: " + grant);
        };
    }
}
