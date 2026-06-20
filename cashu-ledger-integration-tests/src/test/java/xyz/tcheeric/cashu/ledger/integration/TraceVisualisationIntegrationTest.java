package xyz.tcheeric.cashu.ledger.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.web.CashuLedgerWebApplication;

/**
 * Integration test for the US4 graph endpoints (FR-036): /visualisation renders the
 * BFS-reachable node set for an anchor (its count matching the underlying chain), and
 * /stats and /relays advertise index health and schema posture. Exercises the real
 * NIP-98 filter, controller, walk, and visualisation services through the Spring context.
 */
@Tag("integration")
@SpringBootTest(classes = CashuLedgerWebApplication.class)
@AutoConfigureMockMvc
class TraceVisualisationIntegrationTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String PRIV =
            "0000000000000000000000000000000000000000000000000000000000000061";
    private static final String PUB = pub(PRIV);
    private static final String MINT = "https://mint.imani.casa";
    private static final String KEYSET = "00ad12ef";
    private static final String MINT_EVENT_ID = "evtgraphmint00000000000000000000000000000000000000000000000aaa";
    private static final String SWAP_EVENT_ID = "evtgraphswap00000000000000000000000000000000000000000000000bbb";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TraceEventStore traceEventStore;

    @MockBean
    private VoucherLedgerService voucherLedgerService;

    @DynamicPropertySource
    static void authorities(DynamicPropertyRegistry registry) {
        registry.add("trace.security.authorities[0].pubkey", () -> PUB);
        registry.add("trace.security.authorities[0].grants[0]", () -> "trace:read:full");
    }

    @BeforeEach
    void seed() {
        // MINT produces y0; SWAP consumes y0 and produces y1 -> a 2-node downstream chain.
        traceEventStore.store(StoredEvent.of(event(MINT_EVENT_ID, OperationKind.MINT, 1000,
                List.of(), List.of(proof("a1")))));
        traceEventStore.store(StoredEvent.of(event(SWAP_EVENT_ID, OperationKind.SWAP, 2000,
                List.of(proof("a1")), List.of(proof("a2")))));
    }

    /** A downstream walk anchored on the MINT renders both chained nodes under one mint. */
    @Test
    void shouldRenderReachableNodesForAnchor() throws Exception {
        String path = "/api/v1/trace/visualisation?eventId=" + MINT_EVENT_ID + "&direction=down";
        String url = "http://localhost" + path;
        mockMvc.perform(get(path).header("Authorization", nip98(url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mints[0].mintUrl").value(MINT))
                .andExpect(jsonPath("$.mints[0].nodes.length()").value(2));
    }

    /** /stats advertises the indexed event count and current schema version. */
    @Test
    void shouldReportIndexStatsAndSchema() throws Exception {
        String url = "http://localhost/api/v1/trace/stats";
        mockMvc.perform(get("/api/v1/trace/stats").header("Authorization", nip98(url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexedEventCount").value(2))
                .andExpect(jsonPath("$.currentSchemaVersion").value(1));
    }

    /** /relays advertises the relay set and supported schema band. */
    @Test
    void shouldAdvertiseRelaysAndSchemaBand() throws Exception {
        String url = "http://localhost/api/v1/trace/relays";
        mockMvc.perform(get("/api/v1/trace/relays").header("Authorization", nip98(url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentSchemaVersion").value(1))
                .andExpect(jsonPath("$.supportedSchemaVersions[0]").value(1));
    }

    private static TransactionEvent event(String eventId, OperationKind kind, long ms,
                                          List<ProofRef> in, List<ProofRef> out) {
        return new TransactionEvent(
                Optional.of(eventId), "op-" + eventId, kind, MINT, "sat",
                Instant.ofEpochMilli(ms), Instant.ofEpochSecond(ms / 1000), "producerpk",
                Optional.empty(), in, out, List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(0L), Optional.empty(), Optional.empty(), Optional.empty(),
                PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(eventId), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(ms / 1000)));
    }

    private static ProofRef proof(String seed) {
        return new ProofRef(64, KEYSET, "02" + seed.repeat(32),
                Optional.of("s-" + seed), Optional.of("c-" + seed), Optional.empty(), Optional.empty());
    }

    private static String pub(String priv) {
        try {
            return HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(priv)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String nip98(String url) throws Exception {
        long createdAt = Instant.now().getEpochSecond();
        List<List<String>> tags = List.of(List.of("u", url), List.of("method", "GET"));
        String id = CanonicalJson.eventId(PUB, createdAt, 27235, tags, "");
        byte[] sig = Schnorr.sign(HEX.parseHex(id), HEX.parseHex(PRIV), new byte[32]);
        ObjectMapper om = new ObjectMapper();
        ObjectNode ev = om.createObjectNode();
        ev.put("id", id);
        ev.put("pubkey", PUB);
        ev.put("created_at", createdAt);
        ev.put("kind", 27235);
        ArrayNode tagsNode = ev.putArray("tags");
        for (List<String> tag : tags) {
            ArrayNode t = tagsNode.addArray();
            tag.forEach(t::add);
        }
        ev.put("content", "");
        ev.put("sig", HEX.formatHex(sig));
        return "Nostr " + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(om.writeValueAsString(ev).getBytes(StandardCharsets.UTF_8));
    }
}
