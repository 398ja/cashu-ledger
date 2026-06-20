package xyz.tcheeric.cashu.ledger.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

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
import xyz.tcheeric.cashu.ledger.web.CashuLedgerWebApplication;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Security regression test for the trace read API (design §7.3 / SC-008, T038a):
 * reads require NIP-98 auth, a summary-level caller never receives secret-bearing
 * fields, and a full-level caller does. Exercises the real filter + controller +
 * mapper through the Spring context.
 */
@Tag("integration")
@SpringBootTest(classes = CashuLedgerWebApplication.class)
@AutoConfigureMockMvc
class TraceApiSecurityIntegrationTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String FULL_PRIV =
            "0000000000000000000000000000000000000000000000000000000000000041";
    private static final String SUMMARY_PRIV =
            "0000000000000000000000000000000000000000000000000000000000000042";
    private static final String FULL_PUB = pub(FULL_PRIV);
    private static final String SUMMARY_PUB = pub(SUMMARY_PRIV);
    private static final String EVENT_ID = "evtsecuritytest0000000000000000000000000000000000000000000000aa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TraceEventStore traceEventStore;

    @MockBean
    private VoucherLedgerService voucherLedgerService;

    @DynamicPropertySource
    static void authorities(DynamicPropertyRegistry registry) {
        registry.add("trace.security.authorities[0].pubkey", () -> FULL_PUB);
        registry.add("trace.security.authorities[0].grants[0]", () -> "trace:read:full");
        registry.add("trace.security.authorities[1].pubkey", () -> SUMMARY_PUB);
        registry.add("trace.security.authorities[1].grants[0]", () -> "trace:read:summary");
    }

    private static String pub(String priv) {
        try {
            return HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(priv)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void seed() {
        ProofRef in = new ProofRef(64, "00ad12ef", "02" + "a1".repeat(32),
                Optional.of("secret-a1-PLAINTEXT"), Optional.of("0288a1"), Optional.empty(), Optional.empty());
        TransactionEvent event = new TransactionEvent(
                Optional.of(EVENT_ID), "op-sec-1", OperationKind.SWAP, "https://mint.imani.casa", "sat",
                Instant.ofEpochMilli(1740000000123L), Instant.ofEpochSecond(1740000000L),
                "producerpk", Optional.empty(), List.of(in), List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(EVENT_ID), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(1740000000L)));
        traceEventStore.store(StoredEvent.of(event));
    }

    private String nip98(String priv, String pub, String url) throws Exception {
        long createdAt = Instant.now().getEpochSecond();
        List<List<String>> tags = List.of(List.of("u", url), List.of("method", "GET"));
        String id = CanonicalJson.eventId(pub, createdAt, 27235, tags, "");
        byte[] sig = Schnorr.sign(HEX.parseHex(id), HEX.parseHex(priv), new byte[32]);
        ObjectMapper om = new ObjectMapper();
        ObjectNode ev = om.createObjectNode();
        ev.put("id", id);
        ev.put("pubkey", pub);
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

    private String url() {
        return "http://localhost/api/v1/trace/events/" + EVENT_ID;
    }

    /** Tests that an unauthenticated read is rejected with 401. */
    @Test
    void shouldRejectUnauthenticatedRead() throws Exception {
        mockMvc.perform(get("/api/v1/trace/events/{id}", EVENT_ID))
                .andExpect(status().isUnauthorized());
    }

    /** Tests that a summary-level caller is authenticated but never sees secrets. */
    @Test
    void shouldWithholdSecretsFromSummaryCaller() throws Exception {
        mockMvc.perform(get("/api/v1/trace/events/{id}", EVENT_ID)
                        .header("Authorization", nip98(SUMMARY_PRIV, SUMMARY_PUB, url())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("secret-a1-PLAINTEXT"))))
                .andExpect(content().string(containsString("\"returnedPrivacyMode\":\"minimal\"")));
    }

    /** Tests that a full-level caller receives the secret-bearing fields. */
    @Test
    void shouldRevealSecretsToFullCaller() throws Exception {
        mockMvc.perform(get("/api/v1/trace/events/{id}", EVENT_ID)
                        .header("Authorization", nip98(FULL_PRIV, FULL_PUB, url())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("secret-a1-PLAINTEXT")))
                .andExpect(content().string(containsString("\"returnedPrivacyMode\":\"full\"")));
    }
}
