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
 * Unit tests for {@link TraceSyncEngine#handleMessage}: a relay EVENT frame is
 * unwrapped and ingested, and non-EVENT frames are ignored.
 */
class TraceSyncEngineTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String PRIV =
            "000000000000000000000000000000000000000000000000000000000000000b";
    private static final String PUB = derivePub();
    private static final long EVENT_MS = 1740000000123L;
    private static final String MINT = "https://mint.imani.casa";

    private SqliteSidecarIndex index;
    private TraceIngestService ingestService;
    private TraceSyncEngine engine;

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
                new ProducerAttestationConfig(Map.of(MINT, Set.of(PUB))), 1, 60, 86400,
                () -> EVENT_MS + 5000);
        ingestService = new TraceIngestService(new TraceEventMapper(), validator, store, false);
        engine = new TraceSyncEngine(ingestService);
    }

    @AfterEach
    void tearDown() {
        engine.close();
        index.close();
    }

    private static String y(String seed) {
        return "02" + seed.repeat(32);
    }

    private static ProofRef proof(long amount, String seed) {
        return new ProofRef(amount, "00ad12ef", y(seed),
                Optional.of("secret-" + seed), Optional.of("0288" + seed), Optional.empty(), Optional.empty());
    }

    private String eventFrame() throws Exception {
        TransactionEvent event = new TransactionEvent(
                Optional.empty(), "op-1", OperationKind.SWAP, MINT, "sat",
                Instant.ofEpochMilli(EVENT_MS), Instant.ofEpochSecond(EVENT_MS / 1000),
                PUB, Optional.empty(), List.of(proof(64, "a1")), List.of(proof(64, "c3")), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.empty(), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(EVENT_MS / 1000)));
        String id = CanonicalJson.eventId(event);
        byte[] sig = Schnorr.sign(HEX.parseHex(id), HEX.parseHex(PRIV), new byte[32]);
        ObjectMapper om = new ObjectMapper();
        ObjectNode ev = om.createObjectNode();
        ev.put("id", id);
        ev.put("pubkey", PUB);
        ev.put("created_at", event.createdAt().getEpochSecond());
        ev.put("kind", 9079);
        ArrayNode tags = ev.putArray("tags");
        for (List<String> tag : CanonicalJson.tags(event)) {
            ArrayNode t = tags.addArray();
            tag.forEach(t::add);
        }
        ev.put("content", CanonicalJson.content(event));
        ev.put("sig", HEX.formatHex(sig));
        ArrayNode frame = om.createArrayNode();
        frame.add("EVENT");
        frame.add("sub-1");
        frame.add(ev);
        return om.writeValueAsString(frame);
    }

    /** Tests that an EVENT frame is unwrapped and ingested. */
    @Test
    void shouldIngestEventFrame() throws Exception {
        // Act
        engine.handleMessage(eventFrame(), "wss://relay.imani.casa");

        // Then
        assertThat(ingestService.metrics().stored()).isEqualTo(1);
    }

    /** Tests that non-EVENT frames (EOSE, NOTICE) are ignored. */
    @Test
    void shouldIgnoreNonEventFrames() {
        // Act
        engine.handleMessage("[\"EOSE\",\"sub-1\"]", "wss://r");
        engine.handleMessage("[\"NOTICE\",\"hello\"]", "wss://r");

        // Then
        assertThat(ingestService.metrics().stored()).isZero();
    }
}
