package xyz.tcheeric.cashu.ledger.web.security;

import java.util.Optional;
import java.util.Set;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;

/**
 * The authenticated caller of the trace API: their Nostr pubkey and the access
 * levels they hold. Stored as a request attribute by {@link Nip98AuthenticationFilter}
 * and consulted by controllers / the response mapper to shape output (design §7.3).
 */
public record TracePrincipal(String pubkey, Set<TraceAuthority> authorities) {

    /** Request attribute key under which the principal is stored. */
    public static final String ATTRIBUTE = "tracePrincipal";

    public boolean canRead() {
        return authorities.stream().anyMatch(a -> a.readMode().isPresent());
    }

    public boolean isAdmin() {
        return authorities.contains(TraceAuthority.ADMIN);
    }

    /**
     * The most permissive privacy shape this caller may receive: FULL &gt; HASHED &gt;
     * MINIMAL. Empty if the caller holds no read authority.
     */
    public Optional<PrivacyMode> effectiveReadMode() {
        if (authorities.contains(TraceAuthority.READ_FULL)) {
            return Optional.of(PrivacyMode.FULL);
        }
        if (authorities.contains(TraceAuthority.READ_HASHED)) {
            return Optional.of(PrivacyMode.HASHED);
        }
        if (authorities.contains(TraceAuthority.READ_SUMMARY)) {
            return Optional.of(PrivacyMode.MINIMAL);
        }
        return Optional.empty();
    }
}
