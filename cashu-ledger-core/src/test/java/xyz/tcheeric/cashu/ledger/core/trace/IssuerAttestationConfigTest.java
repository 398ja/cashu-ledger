package xyz.tcheeric.cashu.ledger.core.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trust anchor that stops a voucher from certifying itself.
 *
 * <p>{@code VoucherLedgerServiceImpl.verify()} reported {@code signatureValid=true} whenever the
 * signature checked out under the public key carried by the event (audit H-12). Nothing bound
 * that key to the claimed {@code issuer_id}, so anyone who could write to a configured relay
 * could publish a voucher under a key they held, name any issuer, and have the ledger call it
 * valid.
 */
@DisplayName("Issuer attestation binds an issuer id to a key")
class IssuerAttestationConfigTest {

    private static final String ISSUER = "merchant123";
    private static final String REGISTERED_KEY =
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
    private static final String ATTACKER_KEY =
            "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5";

    @Test
    @DisplayName("the registered key is authorised")
    void registeredKeyIsAuthorised() {
        IssuerAttestationConfig config =
                new IssuerAttestationConfig(Map.of(ISSUER, REGISTERED_KEY));

        assertThat(config.isAuthorised(ISSUER, REGISTERED_KEY)).isTrue();
    }

    @Test
    @DisplayName("a different key for the same issuer is not authorised")
    void attackerKeyIsNotAuthorised() {
        IssuerAttestationConfig config =
                new IssuerAttestationConfig(Map.of(ISSUER, REGISTERED_KEY));

        assertThat(config.isAuthorised(ISSUER, ATTACKER_KEY))
                .as("this is the forgery: a real signature by the wrong key")
                .isFalse();
    }

    @Test
    @DisplayName("an unknown issuer is not authorised")
    void unknownIssuerIsNotAuthorised() {
        IssuerAttestationConfig config =
                new IssuerAttestationConfig(Map.of(ISSUER, REGISTERED_KEY));

        assertThat(config.isAuthorised("someone-else", REGISTERED_KEY)).isFalse();
    }

    @Test
    @DisplayName("an empty registry authorises nothing")
    void emptyRegistryAuthorisesNothing() {
        IssuerAttestationConfig config = IssuerAttestationConfig.empty();

        assertThat(config.isEmpty()).isTrue();
        assertThat(config.isAuthorised(ISSUER, REGISTERED_KEY))
                .as("with no registered key there is nothing to check against, so the honest "
                        + "answer is 'not attested', never 'valid'")
                .isFalse();
    }

    @Test
    @DisplayName("issuer ids and keys match case-insensitively")
    void matchingIsCaseInsensitive() {
        IssuerAttestationConfig config =
                new IssuerAttestationConfig(Map.of(ISSUER, REGISTERED_KEY.toUpperCase()));

        assertThat(config.isAuthorised(ISSUER.toUpperCase(), REGISTERED_KEY)).isTrue();
    }

    @Test
    @DisplayName("a null or blank issuer resolves to nothing")
    void nullIssuerResolvesToNothing() {
        IssuerAttestationConfig config =
                new IssuerAttestationConfig(Map.of(ISSUER, REGISTERED_KEY));

        assertThat(config.publicKeyFor(null)).isEmpty();
        assertThat(config.publicKeyFor("  ")).isEmpty();
        assertThat(config.isAuthorised(ISSUER, null)).isFalse();
    }
}
