package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Tests the nostrdb-backed raw event store: durable round-trip of a signed event, the
 * append-only remove no-op, and reindex into a fresh sidecar. Skipped when the nostrdb native
 * library is unavailable.
 */
class NostrDbRawEventStoreTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String PRIV = "000000000000000000000000000000000000000000000000000000000000001f";
    private static final String PUB = pub(PRIV);
    private static final String MINT = "https://mint.imani.casa";

    private NostrDbRawEventStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    /** A signed event stored in nostrdb is retrievable by id, counted, and reindexable. */
    @Test
    void shouldRoundTripAndReindex(@TempDir Path dir) throws Exception {
        store = openOrSkip(dir);
        String json = signedJson(swap("op-1"));
        ParsedTraceEvent parsed = new TraceEventMapper().parse(json, "test");
        StoredEvent signed = new StoredEvent(parsed.event(), Optional.of(parsed.rawJson()));
        String eventId = signed.event().eventId().orElseThrow();

        // When: storing then awaiting nostrdb ingestion
        store.store(signed);
        Optional<StoredEvent> found = await(eventId);

        // Then: the event round-trips with its raw JSON and is counted
        assertThat(found).isPresent();
        assertThat(found.get().event().operationId()).isEqualTo("op-1");
        assertThat(found.get().rawEventJson()).isPresent();
        assertThat(store.count()).isGreaterThanOrEqualTo(1);

        // And: remove is a no-op (append-only), reindex re-projects into a fresh sidecar
        assertThat(store.remove(eventId)).isFalse();
        try (SqliteSidecarIndex index = new SqliteSidecarIndex("jdbc:sqlite::memory:")) {
            assertThat(store.reindex(index)).isGreaterThanOrEqualTo(1);
            assertThat(index.eventIdForOperation("op-1")).contains(eventId);
        }
    }

    private static NostrDbRawEventStore openOrSkip(Path dir) {
        try {
            return NostrDbRawEventStore.open(dir.resolve("ndb"));
        } catch (Throwable t) {
            assumeTrue(false, "nostrdb native library unavailable: " + t.getMessage());
            throw new IllegalStateException(t);
        }
    }

    private Optional<StoredEvent> await(String eventId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Optional<StoredEvent> found = store.findByEventId(eventId);
            if (found.isPresent()) {
                return found;
            }
            Thread.sleep(50);
        }
        return Optional.empty();
    }

    private static TransactionEvent swap(String operationId) {
        long nowSec = Instant.now().getEpochSecond();
        ProofRef in = new ProofRef(64, "00ad12ef", "02" + "a1".repeat(32),
                Optional.of("s"), Optional.of("0288a1"), Optional.empty(), Optional.empty());
        ProofRef out = new ProofRef(64, "00ad12ef", "02" + "b2".repeat(32),
                Optional.of("s2"), Optional.of("0288b2"), Optional.empty(), Optional.empty());
        return new TransactionEvent(
                Optional.empty(), operationId, OperationKind.SWAP, MINT, "sat",
                Instant.ofEpochSecond(nowSec), Instant.ofEpochSecond(nowSec), PUB,
                Optional.empty(), List.of(in), List.of(out), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.empty(), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(nowSec)));
    }

    private static String signedJson(TransactionEvent event) throws Exception {
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

    private static String pub(String priv) {
        try {
            return HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(priv)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
