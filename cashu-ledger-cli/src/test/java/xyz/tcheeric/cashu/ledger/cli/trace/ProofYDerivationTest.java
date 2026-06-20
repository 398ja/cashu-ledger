package xyz.tcheeric.cashu.ledger.cli.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;

/**
 * Verifies that the proof Y the CLI sends is derived locally from the secret via
 * hash_to_curve — the basis for "the raw secret never leaves the machine" (FR-003).
 */
class ProofYDerivationTest {

    /** hash_to_curve of a secret yields a deterministic 33-byte compressed point in hex. */
    @Test
    void shouldDeriveDeterministicCompressedYFromSecret() {
        // Given: a fixed secret
        String secret = "test-secret-0001";

        // When: deriving Y twice (hash_to_curve over the raw secret bytes)
        String first = BDHKEUtils.pointToHex(BDHKEUtils.hashToCurve(secret.getBytes(StandardCharsets.UTF_8)));
        String second = BDHKEUtils.pointToHex(BDHKEUtils.hashToCurve(secret.getBytes(StandardCharsets.UTF_8)));

        // Then: stable, 66 hex chars (33-byte compressed point), starting 02/03
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(66);
        assertThat(first.substring(0, 2)).isIn("02", "03");
    }
}
