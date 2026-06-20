package xyz.tcheeric.cashu.ledger.trace.core;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Applies a {@link PrivacyMode} to a fully-populated {@link ProofRef}, producing
 * the proof reference as it will be serialised on the wire.
 *
 * <ul>
 *   <li>{@link PrivacyMode#FULL} — returns the proof unchanged.</li>
 *   <li>{@link PrivacyMode#HASHED} — replaces {@code secret}, {@code c}, and
 *       {@code witness} with HMAC-SHA-256 hex under the deployment redaction key
 *       and drops {@code dleq}. Bare SHA-256 is deliberately not used: an adversary
 *       holding candidate secrets could otherwise confirm membership (design §5.2).</li>
 *   <li>{@link PrivacyMode#MINIMAL} — drops all secret-bearing fields.</li>
 * </ul>
 *
 * {@code amount}, {@code keysetId}, and {@code y} are preserved in every mode.
 */
public final class ProofRefRedactor {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] redactionKey;

    /**
     * @param redactionKey 256-bit deployment redaction key; required only for
     *                     {@link PrivacyMode#HASHED}. May be {@code null} when the
     *                     redactor will only be used for FULL or MINIMAL.
     */
    public ProofRefRedactor(byte[] redactionKey) {
        this.redactionKey = redactionKey == null ? null : redactionKey.clone();
    }

    /** Convenience factory for deployments that never use HASHED mode. */
    public static ProofRefRedactor withoutKey() {
        return new ProofRefRedactor(null);
    }

    /**
     * Returns {@code proof} serialised according to {@code mode}.
     *
     * @throws IllegalStateException if {@code mode} is HASHED and no key was supplied
     */
    public ProofRef redact(ProofRef proof, PrivacyMode mode) {
        if (proof == null) {
            throw new IllegalArgumentException("proof must not be null");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        return switch (mode) {
            case FULL -> proof;
            case MINIMAL -> new ProofRef(
                    proof.amount(), proof.keysetId(), proof.y(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            case HASHED -> new ProofRef(
                    proof.amount(), proof.keysetId(), proof.y(),
                    proof.secret().map(this::hmacHex),
                    proof.c().map(this::hmacHex),
                    proof.witness().map(this::hmacHex),
                    Optional.empty());
        };
    }

    /**
     * Computes {@code HMAC-SHA-256(redactionKey, value)} as lowercase hex. Used for
     * proof fields and for {@link LightningRef#bolt11()} redaction in HASHED mode.
     *
     * @throws IllegalStateException if no redaction key was supplied
     */
    public String hmacHex(String value) {
        if (redactionKey == null) {
            throw new IllegalStateException(
                    "HASHED redaction requires a redaction key; none was configured");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(redactionKey, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 unavailable in this JVM", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("redaction key rejected by HMAC provider", e);
        }
    }
}
