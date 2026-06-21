package xyz.tcheeric.cashu.ledger.trace.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Unit tests for {@link TraceEventSigner}: the signed event's id equals our
 * canonical id, the signature verifies, and the assembled JSON is well-formed.
 */
class TraceEventSignerTest {

    // A fixed test private key (32 bytes).
    private static final String PRIV_HEX =
            "0000000000000000000000000000000000000000000000000000000000000003";
    private static final HexFormat HEX = HexFormat.of();

    private static String y(String seed) {
        return "02" + seed.repeat(32);
    }

    private TransactionEvent swap(String producerPubkey) {
        ProofRef in = new ProofRef(64, "00ad12ef", y("a1"),
                Optional.of("secret-a1"), Optional.of("0288a1"), Optional.empty(), Optional.empty());
        ProofRef out = new ProofRef(64, "00ad12ef", y("c3"),
                Optional.of("secret-c3"), Optional.of("0288c3"), Optional.empty(), Optional.empty());
        return new TransactionEvent(
                Optional.empty(), "0192f70a-7b3c-7c6e-8a40-1a2b3c4d5e6f", OperationKind.SWAP,
                "https://mint.imani.casa", "sat",
                Instant.ofEpochMilli(1740000000123L), Instant.ofEpochSecond(1740000000L),
                producerPubkey, Optional.empty(), List.of(in), List.of(out), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.empty(), NostrEventMetadata.TRACE_EVENT_KIND,
                        Optional.empty(), Optional.empty(), Instant.ofEpochSecond(1740000000L)));
    }

    /** Tests that the signed event's id equals our canonical event id. */
    @Test
    void shouldSignWithOurCanonicalEventId() {
        // Arrange
        TraceEventSigner signer = new TraceEventSigner(PRIV_HEX);
        TransactionEvent event = swap(signer.publicKeyHex());

        // Act
        SignedTraceEvent signed = signer.sign(event);

        // Then
        assertThat(signed.eventId()).isEqualTo(CanonicalJson.eventId(event));
        assertThat(signed.eventId()).matches("[0-9a-f]{64}");
    }

    /** Tests that the produced Schnorr signature verifies against the event id and pubkey. */
    @Test
    void shouldProduceVerifiableSignature() throws Exception {
        // Arrange
        TraceEventSigner signer = new TraceEventSigner(PRIV_HEX);
        TransactionEvent event = swap(signer.publicKeyHex());

        // Act
        SignedTraceEvent signed = signer.sign(event);

        // Then
        boolean valid = Schnorr.verify(
                HEX.parseHex(signed.eventId()),
                HEX.parseHex(signer.publicKeyHex()),
                HEX.parseHex(signed.signature()));
        assertThat(valid).isTrue();
    }

    /** Tests that the assembled JSON carries the standard Nostr event fields. */
    @Test
    void shouldAssembleWellFormedEventJson() throws Exception {
        // Arrange
        TraceEventSigner signer = new TraceEventSigner(PRIV_HEX);
        TransactionEvent event = swap(signer.publicKeyHex());

        // Act
        SignedTraceEvent signed = signer.sign(event);
        JsonNode json = new ObjectMapper().readTree(signed.eventJson());

        // Then
        assertThat(json.get("id").asText()).isEqualTo(signed.eventId());
        assertThat(json.get("kind").asInt()).isEqualTo(9079);
        assertThat(json.get("pubkey").asText()).isEqualTo(signer.publicKeyHex());
        assertThat(json.get("sig").asText()).isEqualTo(signed.signature());
        assertThat(json.get("created_at").asLong()).isEqualTo(1740000000L);
        assertThat(json.get("tags")).isNotEmpty();
        assertThat(json.get("content").asText()).contains("inputs");
    }

    /** Tests that signing an event whose producer pubkey differs from the key is rejected. */
    @Test
    void shouldRejectMismatchedProducerPubkey() {
        // Arrange
        TraceEventSigner signer = new TraceEventSigner(PRIV_HEX);
        TransactionEvent event = swap("deadbeef");

        // Act / Then
        assertThatThrownBy(() -> signer.sign(event))
                .isInstanceOf(TraceabilityPublishException.class)
                .hasMessageContaining("does not match signer key");
    }

    // Note: definitive relay-compatibility of our canonical NIP-01 serialisation is
    // verified end-to-end against a real relay in the integration tests (T038), the
    // authoritative check (a relay re-derives the id and validates the signature).
}
