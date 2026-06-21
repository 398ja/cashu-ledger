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
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.web.CashuLedgerWebApplication;

/**
 * Integration test for the quote-lifecycle endpoints (T035a / FR-010): a settled quote reports
 * {@code succeeded} via /quotes/{id}/status, and /quotes/{id}/events lists its events.
 */
@Tag("integration")
@SpringBootTest(classes = CashuLedgerWebApplication.class)
@AutoConfigureMockMvc
class TraceQuoteIntegrationTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String PRIV = "000000000000000000000000000000000000000000000000000000000000008a";
    private static final String PUB = pub(PRIV);
    private static final String MINT = "https://mint.imani.casa";
    private static final String QUOTE = "q-int-1";
    private static final String QUOTE_EVENT_ID = "evtquotereq000000000000000000000000000000000000000000000000aaaa";
    private static final String MINT_EVENT_ID = "evtquotemint00000000000000000000000000000000000000000000000bbbb";

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
        traceEventStore.store(StoredEvent.of(quoteRequest()));
        traceEventStore.store(StoredEvent.of(mintSettlement()));
    }

    /** A settled quote reports succeeded and lists both its events. */
    @Test
    void shouldReportSettledQuoteStatusAndEvents() throws Exception {
        java.net.URI statusUri = java.net.URI.create(
                "http://localhost/api/v1/trace/quotes/" + QUOTE + "/status?mintUrl=" + enc(MINT));
        mockMvc.perform(get(statusUri).header("Authorization", nip98(statusUri.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.settledEventId").value(MINT_EVENT_ID));

        java.net.URI eventsUri = java.net.URI.create(
                "http://localhost/api/v1/trace/quotes/" + QUOTE + "/events?mintUrl=" + enc(MINT));
        mockMvc.perform(get(eventsUri).header("Authorization", nip98(eventsUri.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2));
    }

    private static TransactionEvent quoteRequest() {
        LightningRef lightning = new LightningRef(QUOTE, MINT, Optional.empty(), Optional.empty(),
                Optional.of(1000L), Optional.of(Instant.ofEpochSecond(1740000300L)),
                OperationKind.MINT_QUOTE_REQUESTED, false);
        return event(QUOTE_EVENT_ID, OperationKind.MINT_QUOTE_REQUESTED, lightning, List.of());
    }

    private static TransactionEvent mintSettlement() {
        LightningRef lightning = new LightningRef(QUOTE, MINT, Optional.empty(), Optional.empty(),
                Optional.of(1000L), Optional.empty(), OperationKind.MINT_QUOTE_REQUESTED, false);
        ProofRef out = new ProofRef(1000, "00ad12ef", "02" + "a1".repeat(32), Optional.of("s"),
                Optional.of("c"), Optional.empty(), Optional.empty());
        return event(MINT_EVENT_ID, OperationKind.MINT, lightning, List.of(out));
    }

    private static TransactionEvent event(String id, OperationKind kind, LightningRef lightning,
                                          List<ProofRef> outputs) {
        return new TransactionEvent(
                Optional.of(id), "op-" + id, kind, MINT, "sat",
                Instant.ofEpochSecond(1740000000L), Instant.ofEpochSecond(1740000000L), "pk",
                Optional.empty(), List.of(), outputs, List.of(), Optional.of(lightning),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(id), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(1740000000L)));
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
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
