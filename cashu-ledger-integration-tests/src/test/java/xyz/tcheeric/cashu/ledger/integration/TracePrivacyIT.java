package xyz.tcheeric.cashu.ledger.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.web.CashuLedgerWebApplication;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;

/**
 * Integration test for the privacy posture (US7): HASHED/MINIMAL read shaping never returns
 * raw secrets regardless of caller authority, the admin redaction-key registry register/list/
 * verify works for an operator and is forbidden to a reader, and the admin index-status /
 * access-log surfaces are gated (FR-024/032, SC-008).
 */
@Tag("integration")
@SpringBootTest(classes = CashuLedgerWebApplication.class)
@AutoConfigureMockMvc
class TracePrivacyIT {

    private static final HexFormat HEX = HexFormat.of();
    private static final String FULL_PRIV = "0000000000000000000000000000000000000000000000000000000000000071";
    private static final String SUMMARY_PRIV = "0000000000000000000000000000000000000000000000000000000000000072";
    private static final String ADMIN_PRIV = "0000000000000000000000000000000000000000000000000000000000000073";
    private static final String FULL_PUB = pub(FULL_PRIV);
    private static final String SUMMARY_PUB = pub(SUMMARY_PRIV);
    private static final String ADMIN_PUB = pub(ADMIN_PRIV);
    private static final String HASHED_ID = "evthashed00000000000000000000000000000000000000000000000000aaaa";
    private static final String MINIMAL_ID = "evtminimal0000000000000000000000000000000000000000000000000bbbb";

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
        registry.add("trace.security.authorities[2].pubkey", () -> ADMIN_PUB);
        registry.add("trace.security.authorities[2].grants[0]", () -> "trace:admin");
    }

    @BeforeEach
    void seed() {
        traceEventStore.store(StoredEvent.of(hashedEvent()));
        traceEventStore.store(StoredEvent.of(minimalEvent()));
    }

    /** A HASHED-stored event returns hashed (never raw) secrets to a full caller. */
    @Test
    void shouldReturnHashedNotRawSecretsForHashedEvent() throws Exception {
        String url = "http://localhost/api/v1/trace/events/" + HASHED_ID;
        mockMvc.perform(get("/api/v1/trace/events/{id}", HASHED_ID)
                        .header("Authorization", nip98(FULL_PRIV, FULL_PUB, "GET", url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnedPrivacyMode").value("hashed"))
                .andExpect(content().string(containsString("HASHEDSECRET")))
                .andExpect(content().string(not(containsString("RAWSECRET"))));
    }

    /** A MINIMAL-stored event never carries secrets, even to a full caller. */
    @Test
    void shouldWithholdSecretsForMinimalEventEvenFromFullCaller() throws Exception {
        String url = "http://localhost/api/v1/trace/events/" + MINIMAL_ID;
        mockMvc.perform(get("/api/v1/trace/events/{id}", MINIMAL_ID)
                        .header("Authorization", nip98(FULL_PRIV, FULL_PUB, "GET", url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnedPrivacyMode").value("minimal"))
                .andExpect(content().string(not(containsString("RAWSECRET"))));
    }

    /** An operator can register, list, and verify a redaction key; raw key is never returned. */
    @Test
    void shouldRegisterListAndVerifyRedactionKey() throws Exception {
        String keyHex = "11112222333344445555666677778888";
        String registerUrl = "http://localhost/api/v1/trace/admin/redaction-keys";
        mockMvc.perform(post("/api/v1/trace/admin/redaction-keys")
                        .header("Authorization", nip98(ADMIN_PRIV, ADMIN_PUB, "POST", registerUrl))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyId\":\"k1\",\"label\":\"prod\",\"keyHex\":\"" + HEX.formatHex(
                                keyHex.getBytes(StandardCharsets.UTF_8)) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyId").value("k1"));

        mockMvc.perform(get("/api/v1/trace/admin/redaction-keys")
                        .header("Authorization", nip98(ADMIN_PRIV, ADMIN_PUB, "GET", registerUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].keyId").value("k1"));

        String verifyUrl = "http://localhost/api/v1/trace/admin/redaction-keys/k1/verify";
        mockMvc.perform(post("/api/v1/trace/admin/redaction-keys/k1/verify")
                        .header("Authorization", nip98(ADMIN_PRIV, ADMIN_PUB, "POST", verifyUrl))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyHex\":\"" + HEX.formatHex(keyHex.getBytes(StandardCharsets.UTF_8)) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
    }

    /** A read-only caller is forbidden from the admin redaction-key endpoint. */
    @Test
    void shouldForbidNonAdminFromAdminEndpoints() throws Exception {
        String url = "http://localhost/api/v1/trace/admin/redaction-keys";
        mockMvc.perform(get("/api/v1/trace/admin/redaction-keys")
                        .header("Authorization", nip98(SUMMARY_PRIV, SUMMARY_PUB, "GET", url)))
                .andExpect(status().isForbidden());
    }

    /** Admin index-status and access-log are reachable by an operator. */
    @Test
    void shouldExposeIndexStatusAndAccessLogToAdmin() throws Exception {
        String statusUrl = "http://localhost/api/v1/trace/admin/index-status";
        mockMvc.perform(get("/api/v1/trace/admin/index-status")
                        .header("Authorization", nip98(ADMIN_PRIV, ADMIN_PUB, "GET", statusUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").exists());

        String logUrl = "http://localhost/api/v1/trace/admin/access-log";
        mockMvc.perform(get("/api/v1/trace/admin/access-log")
                        .header("Authorization", nip98(ADMIN_PRIV, ADMIN_PUB, "GET", logUrl)))
                .andExpect(status().isOk());
    }

    private static TransactionEvent hashedEvent() {
        ProofRef in = new ProofRef(64, "00ad12ef", "02" + "a1".repeat(32),
                Optional.of("HASHEDSECRET"), Optional.of("0288a1"), Optional.empty(), Optional.empty());
        return event(HASHED_ID, PrivacyMode.HASHED, List.of(in));
    }

    private static TransactionEvent minimalEvent() {
        ProofRef in = new ProofRef(64, "00ad12ef", "02" + "b2".repeat(32),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return event(MINIMAL_ID, PrivacyMode.MINIMAL, List.of(in));
    }

    private static TransactionEvent event(String id, PrivacyMode mode, List<ProofRef> inputs) {
        return new TransactionEvent(
                Optional.of(id), "op-" + id, OperationKind.SWAP, "https://mint.imani.casa", "sat",
                Instant.ofEpochMilli(1740000000123L), Instant.ofEpochSecond(1740000000L), "producerpk",
                Optional.empty(), inputs, List.of(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(0L), Optional.empty(), Optional.empty(), Optional.empty(),
                mode, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(id), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(1740000000L)));
    }

    private static String pub(String priv) {
        try {
            return HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(priv)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String nip98(String priv, String pub, String method, String url) throws Exception {
        long createdAt = Instant.now().getEpochSecond();
        List<List<String>> tags = List.of(List.of("u", url), List.of("method", method));
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
}
