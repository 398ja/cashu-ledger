package xyz.tcheeric.cashu.ledger.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasItem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
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
 * Integration test for the forensic walk (FR-012/014, SC-004): over a mint→swap→melt chain, an
 * upstream walk reaches the MINT root, a downstream walk reaches the MELT, results are
 * deterministic across reruns, and a tight node limit truncates with a continuation cursor.
 */
@Tag("integration")
@SpringBootTest(classes = CashuLedgerWebApplication.class)
@AutoConfigureMockMvc
class TraceWalkIT {

    private static final HexFormat HEX = HexFormat.of();
    private static final String PRIV = "000000000000000000000000000000000000000000000000000000000000009b";
    private static final String PUB = pub(PRIV);
    private static final String MINT = "https://mint.imani.casa";
    private static final String KEYSET = "00ad12ef";
    private static final String Y0 = "02" + "a0".repeat(32);
    private static final String Y1 = "02" + "a1".repeat(32);
    private static final ObjectMapper OM = new ObjectMapper();

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
        // mint -> y0 ; swap consumes y0, produces y1 ; melt consumes y1
        traceEventStore.store(StoredEvent.of(event("e-mint", OperationKind.MINT, 1000,
                List.of(), List.of(proof(Y0)))));
        traceEventStore.store(StoredEvent.of(event("e-swap", OperationKind.SWAP, 2000,
                List.of(proof(Y0)), List.of(proof(Y1)))));
        traceEventStore.store(StoredEvent.of(event("e-melt", OperationKind.MELT, 3000,
                List.of(proof(Y1)), List.of())));
    }

    /** Anchored on Y1, an upstream walk reaches the MINT root and a downstream walk reaches the MELT. */
    @Test
    void shouldWalkUpstreamToMintAndDownstreamToMelt() throws Exception {
        mockMvc.perform(authed(walkUri(Y1, "up")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[*].eventId", hasItem("e-mint")));

        mockMvc.perform(authed(walkUri(Y1, "down")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[*].eventId", hasItem("e-melt")));
    }

    /** The same walk request twice yields the same node ordering. */
    @Test
    void shouldBeDeterministicAcrossReruns() throws Exception {
        String first = body(walkUri(Y1, "both"));
        String second = body(walkUri(Y1, "both"));
        org.assertj.core.api.Assertions.assertThat(nodeIds(first)).isEqualTo(nodeIds(second));
    }

    /** A node limit of 1 truncates and returns a continuation cursor. */
    @Test
    void shouldTruncateWithCursorUnderTightLimit() throws Exception {
        URI uri = URI.create("http://localhost/api/v1/trace/proofs/" + Y1 + "/walk?direction=both&limit=1");
        mockMvc.perform(authed(uri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.cursor").isNotEmpty());
    }

    private static URI walkUri(String y, String direction) {
        return URI.create("http://localhost/api/v1/trace/proofs/" + y + "/walk?direction=" + direction);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(URI uri)
            throws Exception {
        return get(uri).header("Authorization", nip98(uri.toString()));
    }

    private String body(URI uri) throws Exception {
        return mockMvc.perform(authed(uri)).andReturn().getResponse().getContentAsString();
    }

    private static List<String> nodeIds(String json) throws Exception {
        JsonNode nodes = OM.readTree(json).path("nodes");
        return java.util.stream.StreamSupport.stream(nodes.spliterator(), false)
                .map(n -> n.path("eventId").asText()).toList();
    }

    private static TransactionEvent event(String id, OperationKind kind, long ms,
                                          List<ProofRef> in, List<ProofRef> out) {
        return new TransactionEvent(
                Optional.of(id), "op-" + id, kind, MINT, "sat",
                Instant.ofEpochMilli(ms), Instant.ofEpochSecond(ms / 1000), "pk",
                Optional.empty(), in, out, List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(0L), Optional.empty(), Optional.empty(), Optional.empty(),
                PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(id), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(ms / 1000)));
    }

    private static ProofRef proof(String y) {
        return new ProofRef(64, KEYSET, y, Optional.of("s"), Optional.of("c"),
                Optional.empty(), Optional.empty());
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
        ObjectNode ev = OM.createObjectNode();
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
                .encodeToString(OM.writeValueAsString(ev).getBytes(StandardCharsets.UTF_8));
    }
}
