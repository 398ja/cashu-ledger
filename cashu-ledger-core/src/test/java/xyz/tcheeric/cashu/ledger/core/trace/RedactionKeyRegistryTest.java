package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RedactionKeyRegistry}: registration, listing without exposing the raw
 * key, constant-time verification, internal resolution, and duplicate rejection.
 */
class RedactionKeyRegistryTest {

    private static final byte[] MASTER = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY = "abcdefabcdefabcdefabcdefabcdef01".getBytes(StandardCharsets.UTF_8);

    private RedactionKeyRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RedactionKeyRegistry(MASTER, () -> 1_000L);
    }

    /** A registered key is listed by metadata only — never the raw bytes. */
    @Test
    void shouldRegisterAndListMetadataWithoutRawKey() {
        // When: registering a key
        RedactionKeyMetadata meta = registry.register("prod-2026", "Production key", KEY);

        // Then: listing returns the metadata with a fingerprint but no key material
        assertThat(meta.fingerprint()).hasSize(16);
        assertThat(registry.list()).singleElement()
                .satisfies(m -> {
                    assertThat(m.keyId()).isEqualTo("prod-2026");
                    assertThat(m.label()).isEqualTo("Production key");
                });
    }

    /** verify returns true only for the exact registered key. */
    @Test
    void shouldVerifyMatchingKeyOnly() {
        // Given: a registered key
        registry.register("prod-2026", "Production key", KEY);

        // When/Then: the same bytes verify; different bytes do not
        assertThat(registry.verify("prod-2026", KEY)).isTrue();
        assertThat(registry.verify("prod-2026", "wrongwrongwrongwrongwrongwrongwr".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(registry.verify("unknown", KEY)).isFalse();
    }

    /** The raw key round-trips through encryption-at-rest for internal redaction use. */
    @Test
    void shouldResolveRawKeyForInternalUse() {
        // Given: a registered key
        registry.register("prod-2026", "Production key", KEY);

        // When: resolving it
        // Then: the decrypted bytes match the original
        assertThat(registry.resolve("prod-2026")).get().isEqualTo(KEY);
        assertThat(registry.resolve("missing")).isEmpty();
    }

    /** Registering the same id twice is rejected. */
    @Test
    void shouldRejectDuplicateKeyId() {
        // Given: an existing key id
        registry.register("prod-2026", "Production key", KEY);

        // When/Then: re-registering the id fails
        assertThatThrownBy(() -> registry.register("prod-2026", "dup", KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }
}
