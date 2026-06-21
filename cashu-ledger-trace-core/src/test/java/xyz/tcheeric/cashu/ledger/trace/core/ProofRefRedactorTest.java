package xyz.tcheeric.cashu.ledger.trace.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProofRefRedactor}: each privacy mode produces the correct
 * field set, and HASHED uses keyed HMAC (not bare hashing).
 */
class ProofRefRedactorTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    /**
     * Tests that FULL mode returns the proof unchanged, preserving raw secret and C.
     */
    @Test
    void shouldPreserveAllFieldsInFullMode() {
        // Arrange
        ProofRef full = TraceFixtures.fullProof(64, "a1");
        ProofRefRedactor redactor = new ProofRefRedactor(KEY);

        // Act
        ProofRef result = redactor.redact(full, PrivacyMode.FULL);

        // Then
        assertThat(result).isSameAs(full);
        assertThat(result.secret()).contains("secret-a1");
    }

    /**
     * Tests that MINIMAL mode drops all secret-bearing fields but keeps amount,
     * keysetId, and y.
     */
    @Test
    void shouldDropSecretBearingFieldsInMinimalMode() {
        // Arrange
        ProofRef full = TraceFixtures.fullProof(64, "a1");

        // Act
        ProofRef result = ProofRefRedactor.withoutKey().redact(full, PrivacyMode.MINIMAL);

        // Then
        assertThat(result.amount()).isEqualTo(64);
        assertThat(result.keysetId()).isEqualTo(TraceFixtures.KEYSET);
        assertThat(result.y()).isEqualTo(TraceFixtures.y("a1"));
        assertThat(result.secret()).isEmpty();
        assertThat(result.c()).isEmpty();
        assertThat(result.witness()).isEmpty();
        assertThat(result.dleq()).isEmpty();
    }

    /**
     * Tests that HASHED mode replaces secret and C with 64-char hex HMAC digests
     * and drops dleq, while keeping amount/keysetId/y.
     */
    @Test
    void shouldHmacSecretBearingFieldsInHashedMode() {
        // Arrange
        ProofRef full = TraceFixtures.fullProof(64, "a1");
        ProofRefRedactor redactor = new ProofRefRedactor(KEY);

        // Act
        ProofRef result = redactor.redact(full, PrivacyMode.HASHED);

        // Then
        assertThat(result.secret()).isPresent();
        assertThat(result.secret().orElseThrow()).matches("[0-9a-f]{64}");
        assertThat(result.c()).isPresent();
        assertThat(result.y()).isEqualTo(TraceFixtures.y("a1"));
        assertThat(result.dleq()).isEmpty();
    }

    /**
     * Tests that HASHED redaction is keyed: the same plaintext under different keys
     * yields different digests (confirming HMAC, not bare SHA-256).
     */
    @Test
    void shouldProduceDifferentDigestsForDifferentKeys() {
        // Arrange
        byte[] otherKey = "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8);

        // Act
        String a = new ProofRefRedactor(KEY).hmacHex("the-secret");
        String b = new ProofRefRedactor(otherKey).hmacHex("the-secret");

        // Then
        assertThat(a).isNotEqualTo(b);
    }

    /**
     * Tests that requesting HASHED redaction without a configured key fails fast
     * rather than silently producing an unkeyed digest.
     */
    @Test
    void shouldRejectHashedRedactionWithoutKey() {
        // Arrange
        ProofRef full = TraceFixtures.fullProof(64, "a1");
        ProofRefRedactor noKey = ProofRefRedactor.withoutKey();

        // Act / Then
        assertThatThrownBy(() -> noKey.redact(full, PrivacyMode.HASHED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redaction key");
    }

    /**
     * Tests that the same plaintext under the same key hashes deterministically.
     */
    @Test
    void shouldBeDeterministicForSameKeyAndValue() {
        // Arrange
        ProofRefRedactor redactor = new ProofRefRedactor(KEY);

        // Act
        String first = redactor.hmacHex("value");
        String second = redactor.hmacHex("value");

        // Then
        assertThat(first).isEqualTo(second);
    }
}
