package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Maps a voucher issuer id to the public key that issuer is known to sign with.
 *
 * <h2>Why a signature alone proves nothing here</h2>
 *
 * <p>{@code VoucherLedgerServiceImpl.verify()} reported {@code signatureValid=true} whenever the
 * event's signature checked out under the public key the event itself carried (audit H-12).
 * Nothing tied that key to the {@code issuer_id} the voucher claimed, and the relay fetch path
 * did no author check either, so anyone able to write to a configured relay could publish a
 * voucher under their own key with any issuer id they liked and have the ledger pronounce it
 * valid.
 *
 * <p>This is the trust anchor that turns "internally consistent" into "issued by that issuer",
 * the same role {@link ProducerAttestationConfig} plays for trace events.
 *
 * <p>An empty registry means no issuer can be attested. That is deliberate: verification then
 * reports the signature as untrusted rather than valid, which is the honest answer when there is
 * nothing to check against.
 */
public final class IssuerAttestationConfig {

    private final Map<String, String> keysByIssuerId;

    public IssuerAttestationConfig(Map<String, String> keysByIssuerId) {
        this.keysByIssuerId = keysByIssuerId.entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey().toLowerCase(Locale.ROOT),
                e -> e.getValue().toLowerCase(Locale.ROOT)));
    }

    /** A registry that attests to nothing; every signature is reported untrusted. */
    public static IssuerAttestationConfig empty() {
        return new IssuerAttestationConfig(Map.of());
    }

    /** Whether any issuer keys are configured at all. */
    public boolean isEmpty() {
        return keysByIssuerId.isEmpty();
    }

    /** The key registered for an issuer, if any. */
    public Optional<String> publicKeyFor(String issuerId) {
        if (issuerId == null || issuerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(keysByIssuerId.get(issuerId.toLowerCase(Locale.ROOT)));
    }

    /** Whether {@code pubkey} is the key registered for {@code issuerId}. */
    public boolean isAuthorised(String issuerId, String pubkey) {
        if (pubkey == null) {
            return false;
        }
        return publicKeyFor(issuerId)
                .map(registered -> registered.equalsIgnoreCase(pubkey))
                .orElse(false);
    }
}
