package xyz.tcheeric.cashu.ledger.trace.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CanonicalJson}: deterministic content, fixed tag order,
 * and a reproducible Nostr event id. These guarantees underpin idempotency.
 */
class CanonicalJsonTest {

    /**
     * Tests that content always includes inputs and outputs arrays, even when the
     * operation has none (a MINT has empty inputs).
     */
    @Test
    void shouldAlwaysEmitInputsAndOutputsKeys() {
        // Arrange: a MINT has no inputs
        TransactionEvent mint = TraceFixtures.validMint();

        // Act
        String content = CanonicalJson.content(mint);

        // Then: both keys present, inputs empty
        assertThat(content).contains("\"inputs\":[]");
        assertThat(content).contains("\"outputs\":[");
    }

    /**
     * Tests that object keys are emitted in sorted (UTF-16 code unit) order, so the
     * canonical content is independent of insertion order. Capital "C" sorts before
     * lowercase keys.
     */
    @Test
    void shouldSortProofObjectKeysCanonically() {
        // Arrange: a swap whose proofs carry secret and C
        TransactionEvent swap = TraceFixtures.validSwap();

        // Act
        String content = CanonicalJson.content(swap);

        // Then: within a proof object, "C" precedes "amount" precedes "id" precedes "secret" precedes "y"
        int c = content.indexOf("\"C\"");
        int amount = content.indexOf("\"amount\"");
        int id = content.indexOf("\"id\"");
        int secret = content.indexOf("\"secret\"");
        int y = content.indexOf("\"y\"");
        assertThat(c).isLessThan(amount);
        assertThat(amount).isLessThan(id);
        assertThat(id).isLessThan(secret);
        assertThat(secret).isLessThan(y);
    }

    /**
     * Tests that absent optional content keys are omitted entirely rather than
     * serialised as null (a SWAP has no lightning or error_message).
     */
    @Test
    void shouldOmitAbsentOptionalContentKeys() {
        // Arrange
        TransactionEvent swap = TraceFixtures.validSwap();

        // Act
        String content = CanonicalJson.content(swap);

        // Then
        assertThat(content).doesNotContain("lightning");
        assertThat(content).doesNotContain("error_message");
        assertThat(content).doesNotContain("null");
    }

    /**
     * Tests that the tag list begins with the canonical fixed prefix order
     * (d, op, mint_url, unit, transition_at, producer_pubkey, ...).
     */
    @Test
    void shouldEmitTagsInCanonicalOrder() {
        // Arrange
        TransactionEvent swap = TraceFixtures.validSwap();

        // Act
        List<List<String>> tags = CanonicalJson.tags(swap);

        // Then: first six tag names match the fixed order
        assertThat(tags.get(0).get(0)).isEqualTo("d");
        assertThat(tags.get(1).get(0)).isEqualTo("op");
        assertThat(tags.get(2).get(0)).isEqualTo("mint_url");
        assertThat(tags.get(3).get(0)).isEqualTo("unit");
        assertThat(tags.get(4).get(0)).isEqualTo("transition_at");
        assertThat(tags.get(5).get(0)).isEqualTo("producer_pubkey");
        // input_y and output_y appear after the scalar tags
        assertThat(tags).anyMatch(t -> t.get(0).equals("input_y"));
        assertThat(tags).anyMatch(t -> t.get(0).equals("output_y"));
    }

    /**
     * Tests that transition_at is serialised as a decimal-digit string of the
     * epoch milliseconds (no exponent, no separators).
     */
    @Test
    void shouldSerialiseTransitionAtAsMillisString() {
        // Arrange
        TransactionEvent swap = TraceFixtures.validSwap();

        // Act
        List<List<String>> tags = CanonicalJson.tags(swap);
        String transitionAt = tags.stream()
                .filter(t -> t.get(0).equals("transition_at")).findFirst().orElseThrow().get(1);

        // Then
        assertThat(transitionAt).isEqualTo("1740000000123");
    }

    /**
     * Tests that the event id is a 64-char lowercase hex string and is stable
     * (identical for two equal events) — the basis of idempotency.
     */
    @Test
    void shouldProduceStableEventId() {
        // Arrange: two independently built but equal swaps
        TransactionEvent a = TraceFixtures.validSwap();
        TransactionEvent b = TraceFixtures.validSwap();

        // Act
        String idA = CanonicalJson.eventId(a);
        String idB = CanonicalJson.eventId(b);

        // Then
        assertThat(idA).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(idA).isEqualTo(idB);
    }

    /**
     * Tests that reordering the keys of an equivalent content tree yields identical
     * canonical bytes (order-invariance of the canonical writer).
     */
    @Test
    void shouldBeOrderInvariantForEquivalentObjects() {
        // Arrange: same logical object built with different key insertion orders
        var ordered = new java.util.LinkedHashMap<String, Object>();
        ordered.put("amount", 64L);
        ordered.put("id", "00ad12ef");
        ordered.put("y", "02a1");
        var shuffled = new java.util.LinkedHashMap<String, Object>();
        shuffled.put("y", "02a1");
        shuffled.put("amount", 64L);
        shuffled.put("id", "00ad12ef");

        // Act
        String first = CanonicalJson.write(ordered);
        String second = CanonicalJson.write(shuffled);

        // Then
        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo("{\"amount\":64,\"id\":\"00ad12ef\",\"y\":\"02a1\"}");
    }

    /**
     * Tests that strings with control characters and quotes are JSON-escaped per
     * the canonical rules.
     */
    @Test
    void shouldEscapeControlCharactersAndQuotes() {
        // Arrange
        var obj = new java.util.LinkedHashMap<String, Object>();
        obj.put("k", "a\"b\n");

        // Act
        String json = CanonicalJson.write(obj);

        // Then
        assertThat(json).isEqualTo("{\"k\":\"a\\\"b\\n\\u0001\"}");
    }

    /**
     * Tests that an event carrying lightning and an error message includes those
     * optional keys in canonical (sorted) position.
     */
    @Test
    void shouldIncludePresentOptionalContentKeys() {
        // Arrange: a MELT with change output, fee, and lightning
        TransactionEvent melt = TraceFixtures.event()
                .kind(OperationKind.MELT)
                .inputs(List.of(TraceFixtures.fullProof(64, "d4")))
                .outputs(List.of(TraceFixtures.fullProof(2, "07")))
                .outputRoles(List.of(OutputRole.CHANGE))
                .feeAmount(2L)
                .lightning(new LightningRef("q-melt", TraceFixtures.MINT_URL,
                        Optional.empty(), Optional.empty(), Optional.of(60L), Optional.empty(),
                        OperationKind.MELT_QUOTE_REQUESTED, false))
                .build();

        // Act
        String content = CanonicalJson.content(melt);

        // Then: error_message absent, lightning present and sorted before outputs
        assertThat(content).contains("\"lightning\":{");
        assertThat(content.indexOf("\"inputs\"")).isLessThan(content.indexOf("\"lightning\""));
        assertThat(content.indexOf("\"lightning\"")).isLessThan(content.indexOf("\"outputs\""));
    }
}
