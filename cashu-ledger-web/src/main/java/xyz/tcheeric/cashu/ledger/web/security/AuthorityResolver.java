package xyz.tcheeric.cashu.ledger.web.security;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a caller pubkey to the trace authorities it holds, from static config
 * (design §7.3). A pubkey with no configured grants resolves to an empty set and is
 * refused by the filter.
 */
public final class AuthorityResolver {

    private final Map<String, Set<TraceAuthority>> byPubkey = new HashMap<>();

    public AuthorityResolver(TraceSecurityProperties properties) {
        for (TraceSecurityProperties.AuthorityEntry entry : properties.getAuthorities()) {
            if (entry.getPubkey() == null) {
                continue;
            }
            Set<TraceAuthority> grants = EnumSet.noneOf(TraceAuthority.class);
            for (String grant : entry.getGrants()) {
                grants.add(TraceAuthority.fromGrant(grant));
            }
            byPubkey.put(entry.getPubkey().toLowerCase(Locale.ROOT), grants);
        }
    }

    /** The authorities held by {@code pubkey}; empty if none are configured. */
    public Set<TraceAuthority> resolve(String pubkey) {
        return byPubkey.getOrDefault(pubkey.toLowerCase(Locale.ROOT), Set.of());
    }
}
