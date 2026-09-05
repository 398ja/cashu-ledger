package xyz.tcheeric.cashu.ledger.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.core.trace.IssuerAttestationConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification reports {@code signatureValid=false} unless the issuer's key is attested, and
 * {@code verify} exits non-zero when it does. That fail-closed default is correct, but it is only
 * defensible if an operator can actually change it.
 *
 * <p>When the H-12 fix landed there was no way to: {@code IssuerAttestationConfig} was constructed
 * as {@code empty()} at every one of the nine CLI call sites and the sole web bean, with no
 * property, no option and no binding anywhere. So every {@code verify} invocation exited non-zero
 * with no supported remedy, which is not fail-closed but fail-always, and it would have broken any
 * CI job or monitor gating on that exit code.
 *
 * <p>These tests pin that the option exists and reaches the registry.
 */
@DisplayName("CLI issuer key configuration")
class CashuLedgerCommandIssuerKeyTest {

    private static final String ISSUER = "corner-cafe";
    private static final String PUBKEY =
            "e2ccf7cf20403f3f2a4a55b328f0de3be38558a7d5f33632fdaaefc726c1c8eb";

    @Test
    @DisplayName("no --issuer-key means nothing is trusted")
    void defaultRegistryIsEmpty() {
        CashuLedgerCommand command = new CashuLedgerCommand();
        new CommandLine(command).parseArgs();

        assertThat(command.issuerAttestation().isEmpty())
                .as("the default must stay fail-closed; this is what the option exists to change")
                .isTrue();
    }

    @Test
    @DisplayName("--issuer-key attests the given issuer")
    void issuerKeyOptionPopulatesTheRegistry() {
        CashuLedgerCommand command = new CashuLedgerCommand();
        new CommandLine(command).parseArgs("--issuer-key", ISSUER + "=" + PUBKEY);

        IssuerAttestationConfig config = command.issuerAttestation();

        assertThat(config.isEmpty())
                .as("the whole defect was that no input could make this false")
                .isFalse();
        assertThat(config.publicKeyFor(ISSUER)).contains(PUBKEY);
        assertThat(config.isAuthorised(ISSUER, PUBKEY)).isTrue();
    }

    @Test
    @DisplayName("an issuer key does not attest a different key for the same issuer")
    void wrongKeyForKnownIssuerIsNotAuthorised() {
        CashuLedgerCommand command = new CashuLedgerCommand();
        new CommandLine(command).parseArgs("--issuer-key", ISSUER + "=" + PUBKEY);

        // Configuring one issuer must not weaken the check into "some key is configured".
        assertThat(command.issuerAttestation().isAuthorised(ISSUER,
                "0000000000000000000000000000000000000000000000000000000000000000"))
                .isFalse();
    }

    @Test
    @DisplayName("an unconfigured issuer stays untrusted even when others are configured")
    void unknownIssuerIsNotAuthorised() {
        CashuLedgerCommand command = new CashuLedgerCommand();
        new CommandLine(command).parseArgs("--issuer-key", ISSUER + "=" + PUBKEY);

        assertThat(command.issuerAttestation().isAuthorised("someone-else", PUBKEY)).isFalse();
    }

    @Test
    @DisplayName("--issuer-key is repeatable")
    void multipleIssuersCanBeAttested() {
        String other = "0000000000000000000000000000000000000000000000000000000000000001";

        CashuLedgerCommand command = new CashuLedgerCommand();
        new CommandLine(command).parseArgs(
                "--issuer-key", ISSUER + "=" + PUBKEY,
                "--issuer-key", "other-shop=" + other);

        assertThat(command.issuerAttestation().isAuthorised(ISSUER, PUBKEY)).isTrue();
        assertThat(command.issuerAttestation().isAuthorised("other-shop", other)).isTrue();
    }

    @Test
    @DisplayName("issuer ids and keys match case-insensitively")
    void matchingIsCaseInsensitive() {
        CashuLedgerCommand command = new CashuLedgerCommand();
        new CommandLine(command).parseArgs("--issuer-key", ISSUER + "=" + PUBKEY.toUpperCase());

        assertThat(command.issuerAttestation().isAuthorised(ISSUER.toUpperCase(), PUBKEY))
                .as("hex case is not semantic; a case mismatch must not read as a forgery")
                .isTrue();
    }
}
