package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Unit tests for {@link TraceEventMapper} and {@link TraceIngestValidator}: a signed
 * event round-trips through serialise → parse, and the validator accepts well-formed
 * events while rejecting tampering, unauthorised producers, clock skew, and
 * invariant violations.
 */
class TraceIngestTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String PRIV =
            "0000000000000000000000000000000000000000000000000000000000000007";
    private static final String PUB = derivePub();
    private static final long EVENT_MS = 1740000000123L;

    private final TraceEventMapper mapper = new TraceEventMapper();

    private static String derivePub() {
        try {
            return HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(PRIV)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String y(String seed) {
        return "02" + seed.repeat(32);
    }

    private static ProofRef proof(long amount, String seed) {
        return new ProofRef(amount, "00ad12ef", y(seed),
                Optional.of("secret-" + seed), Optional.of("0288" + seed), Optional.empty(), Optional.empty());
    }

    private TransactionEvent swap(List<ProofRef> inputs, List<ProofRef> outputs) {
        return new TransactionEvent(
                Optional.empty(), "op-1", OperationKind.SWAP, "https://mint.imani.casa", "sat",
                Instant.ofEpochMilli(EVENT_MS), Instant.ofEpochSecond(EVENT_MS / 1000),
                PUB, Optional.empty(), inputs, outputs, List.of(),
                Optional.empty(), Optional.of("v-1"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.empty(), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(EVENT_MS / 1000)));
    }

    /** Produces a properly Schnorr-signed event JSON, mirroring the producer SDK. */
    private String signedJson(TransactionEvent event) throws Exception {
        String id = CanonicalJson.eventId(event);
        byte[] sig = Schnorr.sign(HEX.parseHex(id), HEX.parseHex(PRIV), new byte[32]);
        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();
        root.put("id", id);
        root.put("pubkey", PUB);
        root.put("created_at", event.createdAt().getEpochSecond());
        root.put("kind", 9079);
        ArrayNode tags = root.putArray("tags");
        for (List<String> tag : CanonicalJson.tags(event)) {
            ArrayNode t = tags.addArray();
            tag.forEach(t::add);
        }
        root.put("content", CanonicalJson.content(event));
        root.put("sig", HEX.formatHex(sig));
        return om.writeValueAsString(root);
    }

    private TraceIngestValidator validatorAt(long nowMs, Set<String> signers) {
        ProducerAttestationConfig producers = new ProducerAttestationConfig(
                Map.of("https://mint.imani.casa", signers));
        return new TraceIngestValidator(producers, 1, 60, 24 * 60 * 60, () -> nowMs);
    }

    /** Tests that a signed swap round-trips back into an equivalent event. */
    @Test
    void shouldRoundTripSignedEvent() throws Exception {
        // Arrange
        TransactionEvent original = swap(List.of(proof(64, "a1"), proof(64, "b2")),
                List.of(proof(128, "c3")));

        // Act
        ParsedTraceEvent parsed = mapper.parse(signedJson(original), "wss://relay.imani.casa");

        // Then
        TransactionEvent e = parsed.event();
        assertThat(e.eventId()).contains(CanonicalJson.eventId(original));
        assertThat(e.kind()).isEqualTo(OperationKind.SWAP);
        assertThat(e.mintUrl()).isEqualTo("https://mint.imani.casa");
        assertThat(e.operationId()).isEqualTo("op-1");
        assertThat(e.inputs()).hasSize(2);
        assertThat(e.outputs().get(0).y()).isEqualTo(y("c3"));
        assertThat(e.voucherRef()).contains("v-1");
        assertThat(e.source().relayUrl()).contains("wss://relay.imani.casa");
    }

    /** Tests that a well-formed, authorised, recent event passes validation. */
    @Test
    void shouldAcceptValidEvent() throws Exception {
        // Arrange
        TransactionEvent original = swap(List.of(proof(64, "a1")), List.of(proof(64, "c3")));
        ParsedTraceEvent parsed = mapper.parse(signedJson(original), null);

        // Act
        Optional<IngestRejection> rejection =
                validatorAt(EVENT_MS + 5000, Set.of(PUB)).validate(parsed, false);

        // Then
        assertThat(rejection).isEmpty();
    }

    /** Tests that tampering the content invalidates the canonical id. */
    @Test
    void shouldRejectTamperedEvent() throws Exception {
        // Arrange: sign, then tamper the content so the recomputed id no longer matches
        TransactionEvent original = swap(List.of(proof(64, "a1")), List.of(proof(64, "c3")));
        String json = signedJson(original).replace("\\\"amount\\\":64", "\\\"amount\\\":999");
        ParsedTraceEvent parsed = mapper.parse(json, null);

        // Act
        Optional<IngestRejection> rejection =
                validatorAt(EVENT_MS + 5000, Set.of(PUB)).validate(parsed, false);

        // Then
        assertThat(rejection).map(IngestRejection::code).contains("INVALID_ID");
    }

    /** Tests that an event signed by an unregistered producer is rejected. */
    @Test
    void shouldRejectUnauthorisedProducer() throws Exception {
        // Arrange: attestation allows a different key
        TransactionEvent original = swap(List.of(proof(64, "a1")), List.of(proof(64, "c3")));
        ParsedTraceEvent parsed = mapper.parse(signedJson(original), null);

        // Act
        Optional<IngestRejection> rejection =
                validatorAt(EVENT_MS + 5000, Set.of("deadbeef")).validate(parsed, false);

        // Then
        assertThat(rejection).map(IngestRejection::code).contains("TRACE_FORBIDDEN_PRODUCER");
    }

    /** Tests that an event dated too far in the future is rejected. */
    @Test
    void shouldRejectFutureClockSkew() throws Exception {
        // Arrange: now is well before the event time
        TransactionEvent original = swap(List.of(proof(64, "a1")), List.of(proof(64, "c3")));
        ParsedTraceEvent parsed = mapper.parse(signedJson(original), null);

        // Act
        Optional<IngestRejection> rejection =
                validatorAt(EVENT_MS - 3_600_000, Set.of(PUB)).validate(parsed, false);

        // Then
        assertThat(rejection).map(IngestRejection::code).contains("CLOCK_SKEW_FUTURE");
    }

    /** Tests that a stale event is rejected unless historical ingestion is allowed. */
    @Test
    void shouldRejectStaleUnlessAllowed() throws Exception {
        // Arrange: now is far after the event (> 24h)
        TransactionEvent original = swap(List.of(proof(64, "a1")), List.of(proof(64, "c3")));
        ParsedTraceEvent parsed = mapper.parse(signedJson(original), null);
        long farFuture = EVENT_MS + 3L * 24 * 60 * 60 * 1000;

        // Act
        Optional<IngestRejection> rejected = validatorAt(farFuture, Set.of(PUB)).validate(parsed, false);
        Optional<IngestRejection> allowed = validatorAt(farFuture, Set.of(PUB)).validate(parsed, true);

        // Then
        assertThat(rejected).map(IngestRejection::code).contains("CLOCK_SKEW_STALE");
        assertThat(allowed).isEmpty();
    }

    /** Tests that an unbalanced swap is rejected by the invariant check. */
    @Test
    void shouldRejectInvalidOperation() throws Exception {
        // Arrange: 64 in, 100 out
        TransactionEvent original = swap(List.of(proof(64, "a1")), List.of(proof(100, "c3")));
        ParsedTraceEvent parsed = mapper.parse(signedJson(original), null);

        // Act
        Optional<IngestRejection> rejection =
                validatorAt(EVENT_MS + 5000, Set.of(PUB)).validate(parsed, false);

        // Then
        assertThat(rejection).map(IngestRejection::code).contains("INVALID_OPERATION");
    }

    /** Tests that a MELT's lightning reference round-trips via the content payload. */
    @Test
    void shouldRoundTripLightning() throws Exception {
        // Arrange
        TransactionEvent melt = new TransactionEvent(
                Optional.empty(), "op-melt", OperationKind.MELT, "https://mint.imani.casa", "sat",
                Instant.ofEpochMilli(EVENT_MS), Instant.ofEpochSecond(EVENT_MS / 1000),
                PUB, Optional.empty(), List.of(proof(64, "d4")), List.of(), List.of(),
                Optional.of(new LightningRef("q-1", "https://mint.imani.casa", Optional.empty(),
                        Optional.empty(), Optional.of(60L), Optional.empty(),
                        OperationKind.MELT_QUOTE_REQUESTED, false)),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(4L), Optional.empty(), Optional.empty(),
                Optional.empty(), PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.empty(), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(EVENT_MS / 1000)));

        // Act
        ParsedTraceEvent parsed = mapper.parse(signedJson(melt), null);

        // Then
        assertThat(parsed.event().lightning()).isPresent();
        assertThat(parsed.event().lightning().orElseThrow().quoteId()).isEqualTo("q-1");
        assertThat(parsed.event().lightning().orElseThrow().amount()).contains(60L);
    }
}
