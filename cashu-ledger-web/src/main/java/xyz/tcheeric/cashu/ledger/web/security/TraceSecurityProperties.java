package xyz.tcheeric.cashu.ledger.web.security;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Trace API security configuration (design §7.3): the authority grants per caller
 * pubkey and the NIP-98 timestamp tolerance.
 */
@Data
@ConfigurationProperties(prefix = "trace.security")
public class TraceSecurityProperties {

    /** Allowed NIP-98 auth-event clock skew, in seconds. */
    private long authSkewSeconds = 60;

    /**
     * 32-byte AES master key (hex) that encrypts registered redaction keys at rest. The
     * default is a development placeholder; production deployments MUST override it.
     */
    private String redactionMasterKeyHex =
            "0000000000000000000000000000000000000000000000000000000000000000";

    /** Per-pubkey authority grants. */
    private List<AuthorityEntry> authorities = new ArrayList<>();

    /** A caller pubkey and the grant strings it holds (e.g. {@code trace:read:full}). */
    @Data
    public static class AuthorityEntry {
        private String pubkey;
        private List<String> grants = new ArrayList<>();
    }
}
