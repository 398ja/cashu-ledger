package xyz.tcheeric.cashu.ledger.trace.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link SchemaCompatibility} four-tier ladder (design §5.12): which versions
 * are accepted, deprecated, unsupported, or future, and the advertised support/deprecation bands.
 */
class SchemaCompatibilityTest {

    /** Against a ledger at N=5, each version maps to the expected tier. */
    @Test
    void shouldClassifyEachTier() {
        assertThat(SchemaCompatibility.classify(6, 5)).isEqualTo(SchemaCompatibility.Tier.FUTURE);
        assertThat(SchemaCompatibility.classify(5, 5)).isEqualTo(SchemaCompatibility.Tier.ACCEPTED);
        assertThat(SchemaCompatibility.classify(4, 5)).isEqualTo(SchemaCompatibility.Tier.ACCEPTED);
        assertThat(SchemaCompatibility.classify(3, 5)).isEqualTo(SchemaCompatibility.Tier.DEPRECATED);
        assertThat(SchemaCompatibility.classify(2, 5)).isEqualTo(SchemaCompatibility.Tier.UNSUPPORTED);
    }

    /** The advertised support band is N-2..N and the deprecated subset is [N-2]. */
    @Test
    void shouldAdvertiseSupportAndDeprecationBands() {
        assertThat(SchemaCompatibility.supportedVersions(5)).containsExactly(3, 4, 5);
        assertThat(SchemaCompatibility.deprecatedVersions(5)).containsExactly(3);
    }

    /** For the v1 ledger there are no deprecated versions and the band is just [1]. */
    @Test
    void shouldHaveNoDeprecatedVersionsAtVersionOne() {
        assertThat(SchemaCompatibility.supportedVersions(1)).containsExactly(1);
        assertThat(SchemaCompatibility.deprecatedVersions(1)).isEmpty();
        assertThat(SchemaCompatibility.isDeprecated(1, 1)).isFalse();
    }
}
