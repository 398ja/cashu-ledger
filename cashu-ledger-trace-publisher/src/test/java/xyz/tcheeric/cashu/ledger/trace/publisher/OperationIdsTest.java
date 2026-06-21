package xyz.tcheeric.cashu.ledger.trace.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OperationIds}: UUIDv7 format and time-ordering, and
 * deterministic UUIDv5-based backfill ids.
 */
class OperationIdsTest {

    private static final byte[] RAND = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

    /** Tests that a generated id is a valid version-7 UUID. */
    @Test
    void shouldGenerateVersion7Uuid() {
        // Act
        UUID id = UUID.fromString(OperationIds.uuidV7(1_700_000_000_000L, RAND));

        // Then
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // RFC 4122 / 9562 variant
    }

    /** Tests that UUIDv7 ids are time-ordered: an earlier timestamp sorts first. */
    @Test
    void shouldBeTimeOrdered() {
        // Arrange
        String earlier = OperationIds.uuidV7(1_000L, RAND);
        String later = OperationIds.uuidV7(2_000L, RAND);

        // Then
        assertThat(earlier).isLessThan(later);
    }

    /** Tests that a UUIDv5 is deterministic for the same name and is version 5. */
    @Test
    void shouldGenerateDeterministicVersion5Uuid() {
        // Act
        String a = OperationIds.uuidV5("cashu-ledger-trace|mint|x");
        String b = OperationIds.uuidV5("cashu-ledger-trace|mint|x");

        // Then
        assertThat(a).isEqualTo(b);
        assertThat(UUID.fromString(a).version()).isEqualTo(5);
    }

    /** Tests that distinct backfill contexts yield distinct, stable ids. */
    @Test
    void shouldDeriveStableBackfillIds() {
        // Arrange
        String first = OperationIds.backfillId("https://mint.imani.casa", "swap",
                "2026-02-19T12:01:30.456Z", List.of("00ad12ef:02a1"), List.of("00ad12ef:02c3"));
        String same = OperationIds.backfillId("https://mint.imani.casa", "swap",
                "2026-02-19T12:01:30.456Z", List.of("00ad12ef:02a1"), List.of("00ad12ef:02c3"));
        String different = OperationIds.backfillId("https://mint.imani.casa", "swap",
                "2026-02-19T12:01:30.456Z", List.of("00ad12ef:02b2"), List.of("00ad12ef:02c3"));

        // Then
        assertThat(first).isEqualTo(same);
        assertThat(first).isNotEqualTo(different);
    }
}
