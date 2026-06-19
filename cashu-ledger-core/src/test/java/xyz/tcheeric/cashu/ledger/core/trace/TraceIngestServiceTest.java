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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Unit tests for {@link TraceIngestService}: the parse → conflict/dedup → validate
 * → store pipeline, including idempotency and operation-conflict detection.
 */
class TraceIngestServiceTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String PRIV =
            "0000000000000000000000000000000000000000000000000000000000000009";
    private static final String PUB = derivePub();
    private static final long EVENT_MS = 1740000000123L;
    private static final String MINT = "https://mint.imani.casa";

    private SqliteSidecarIndex index;
    private TraceIngestService service;

    private static String derivePub() {
        try {
            return HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(PRIV)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        IndexedTraceEventStore store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        TraceIngestValidator validator = new TraceIngestValidator(
                new ProducerAttestationConfig(Map.of(MINT, Set.of(PUB))), 1, 60, 24 * 60 * 60,
                () -> EVENT_MS + 5000);
        service = new TraceIngestService(new TraceEventMapper(), validator, store, false);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    private static String y(String seed) {
        return "02" + seed.repeat(32);
    }

    private static ProofRef proof(long amount, String seed) {
        return new ProofRef(amount, "00ad12ef", y(seed),
                Optional.of("secret-" + seed), Optional.of("0288" + seed), Optional.empty(), Optional.empty());
    }

    private TransactionEvent swap(String operationId, List<ProofRef> in, List<ProofRef> out) {
        return new TransactionEvent(
                Optional.empty(), operationId, OperationKind.SWAP, MINT, "sat",
                Instant.ofEpochMilli(EVENT_MS), Instant.ofEpochSecond(EVENT_MS / 1000),
                PUB, Optional.empty(), in, out, List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.empty(), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(EVENT_MS / 1000)));
    }

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

    /** Tests that a valid event is stored and counted. */
    @Test
    void shouldStoreValidEvent() throws Exception {
        // Act
        IngestOutcome outcome = service.ingest(
                signedJson(swap("op-1", List.of(proof(64, "a1")), List.of(proof(64, "c3")))), "wss://r");

        // Then
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.STORED);
        assertThat(service.metrics().stored()).isEqualTo(1);
    }

    /** Tests that re-ingesting the same event is a no-op duplicate. */
    @Test
    void shouldDeduplicateSameEvent() throws Exception {
        // Arrange
        String json = signedJson(swap("op-1", List.of(proof(64, "a1")), List.of(proof(64, "c3"))));

        // Act
        IngestOutcome first = service.ingest(json, "wss://r1");
        IngestOutcome second = service.ingest(json, "wss://r2");

        // Then
        assertThat(first.status()).isEqualTo(IngestOutcome.Status.STORED);
        assertThat(second.status()).isEqualTo(IngestOutcome.Status.DUPLICATE);
        assertThat(service.metrics().stored()).isEqualTo(1);
        assertThat(service.metrics().duplicates()).isEqualTo(1);
    }

    /** Tests that a malformed payload is rejected without storing. */
    @Test
    void shouldRejectMalformed() {
        // Act
        IngestOutcome outcome = service.ingest("{not valid", "wss://r");

        // Then
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.REJECTED);
        assertThat(service.metrics().rejected()).isEqualTo(1);
    }

    /** Tests that a second event reusing an operation id with different content conflicts. */
    @Test
    void shouldDetectOperationConflict() throws Exception {
        // Arrange: same op-1, different proof sets -> different event ids
        service.ingest(signedJson(swap("op-1", List.of(proof(64, "a1")), List.of(proof(64, "c3")))), "wss://r");

        // Act: same operation id, different outputs
        IngestOutcome conflict = service.ingest(
                signedJson(swap("op-1", List.of(proof(64, "a1")), List.of(proof(64, "d4")))), "wss://r");

        // Then
        assertThat(conflict.status()).isEqualTo(IngestOutcome.Status.CONFLICT);
        assertThat(conflict.rejection()).map(IngestRejection::code).contains("OPERATION_CONFLICT");
        assertThat(service.metrics().conflicts()).isEqualTo(1);
    }

    /** Tests that an event from an unauthorised producer is rejected at validation. */
    @Test
    void shouldRejectUnauthorisedProducer() throws Exception {
        // Arrange: validator allowing only a different key
        TraceIngestValidator strict = new TraceIngestValidator(
                new ProducerAttestationConfig(Map.of(MINT, Set.of("deadbeef"))), 1, 60, 86400,
                () -> EVENT_MS + 5000);
        IndexedTraceEventStore freshStore =
                new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        TraceIngestService strictService =
                new TraceIngestService(new TraceEventMapper(), strict, freshStore, false);

        // Act
        IngestOutcome outcome = strictService.ingest(
                signedJson(swap("op-9", List.of(proof(64, "a1")), List.of(proof(64, "c3")))), "wss://r");

        // Then
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.REJECTED);
        assertThat(outcome.rejection()).map(IngestRejection::code).contains("TRACE_FORBIDDEN_PRODUCER");
    }
}
