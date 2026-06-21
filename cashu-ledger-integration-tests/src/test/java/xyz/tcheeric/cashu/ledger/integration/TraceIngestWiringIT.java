package xyz.tcheeric.cashu.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.trace.IngestOutcome;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestService;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.web.CashuLedgerWebApplication;
import xyz.tcheeric.cashu.ledger.web.trace.TraceStreamBroadcaster;

/**
 * Integration test for the ingest autoconfiguration (design §6.1). With
 * {@code trace.ingest.enabled=true} the ingest service, sync engine, and voucher watcher are
 * wired into the context (no relays configured, so no connections are opened). Verifies a
 * validly-signed kind-9079 event flows through the validating ingest service into storage, the
 * activity cache, and the live-stream broadcaster.
 */
@Tag("integration")
@SpringBootTest(classes = CashuLedgerWebApplication.class)
class TraceIngestWiringIT {

    private static final HexFormat HEX = HexFormat.of();
    private static final String PRIV = "000000000000000000000000000000000000000000000000000000000000000a";
    private static final String PUB = pub(PRIV);
    private static final String MINT = "https://mint.imani.casa";

    @Autowired
    private TraceIngestService ingestService;

    @Autowired
    private TraceEventStore store;

    @Autowired
    private TraceStreamBroadcaster broadcaster;

    @MockBean
    private VoucherLedgerService voucherLedgerService;

    @DynamicPropertySource
    static void ingestProperties(DynamicPropertyRegistry registry) {
        registry.add("trace.ingest.enabled", () -> "true");
        registry.add("ledger.web.relays", () -> ""); // no relay connections in-test
        registry.add("trace.ingest.producers[0].mintUrl", () -> MINT);
        registry.add("trace.ingest.producers[0].pubkeys[0]", () -> PUB);
    }

    /** A signed event ingests through the wired pipeline into storage and notifies subscribers. */
    @Test
    void shouldIngestSignedEventThroughWiredPipeline() throws Exception {
        // Given: a live-stream subscriber and a validly-signed swap event
        broadcaster.subscribe(5_000);
        String json = signedJson(swap());

        // When: ingesting it through the wired service
        IngestOutcome outcome = ingestService.ingest(json, "wss://relay");

        // Then: stored, counted, and the subscriber remains registered (publish did not drop it)
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.STORED);
        assertThat(ingestService.metrics().stored()).isEqualTo(1);
        assertThat(store.findByEventId(outcome.eventId().orElseThrow())).isPresent();
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
    }

    private static TransactionEvent swap() {
        long nowSec = Instant.now().getEpochSecond();
        ProofRef in = new ProofRef(64, "00ad12ef", "02" + "a1".repeat(32),
                Optional.of("secret-a1"), Optional.of("0288a1"), Optional.empty(), Optional.empty());
        ProofRef out = new ProofRef(64, "00ad12ef", "02" + "b2".repeat(32),
                Optional.of("secret-b2"), Optional.of("0288b2"), Optional.empty(), Optional.empty());
        return new TransactionEvent(
                Optional.empty(), "op-wiring-1", OperationKind.SWAP, MINT, "sat",
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
