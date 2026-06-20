package xyz.tcheeric.cashu.ledger.integration;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import xyz.tcheeric.cashu.ledger.core.model.BackingStrategy;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStateMetadata;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.trace.IssuerBackfill;
import xyz.tcheeric.cashu.ledger.core.trace.ProofIdentity;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex;
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
 * End-to-end test for the voucher↔token cross-link (US3, SC-010 / FR-024): a SEND carrying
 * a voucher_ref is navigable from both the voucher inspect API and the trace voucher
 * endpoint, and an issuer-less event becomes discoverable by issuer once its voucher
 * back-fills the issuer. Exercises the real filter, controllers, mapper, sidecar, and
 * back-fill through the Spring context.
 */
@Tag("integration")
@SpringBootTest(classes = CashuLedgerWebApplication.class)
@AutoConfigureMockMvc
class TraceVoucherLinkIT {

    private static final HexFormat HEX = HexFormat.of();
    private static final String FULL_PRIV =
            "0000000000000000000000000000000000000000000000000000000000000051";
    private static final String FULL_PUB = pub(FULL_PRIV);
    private static final String MINT = "https://mint.imani.casa";
    private static final String SEND_EVENT_ID = "evtvoucherlink000000000000000000000000000000000000000000000aa";
    private static final String MINT_EVENT_ID = "evtissuerlink0000000000000000000000000000000000000000000000bb";
    private static final String VOUCHER_ID = "v1";
    private static final String ISSUER_ID = "iss-1";
    private static final String SEND_Y = "02" + "a1".repeat(32);
    private static final String MINT_Y = "02" + "b2".repeat(32);
    private static final String KEYSET = "00ad12ef";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TraceEventStore traceEventStore;

    @Autowired
    private SqliteSidecarIndex sidecarIndex;

    @MockBean
    private VoucherLedgerService voucherLedgerService;

    @DynamicPropertySource
    static void authorities(DynamicPropertyRegistry registry) {
        registry.add("trace.security.authorities[0].pubkey", () -> FULL_PUB);
        registry.add("trace.security.authorities[0].grants[0]", () -> "trace:read:full");
    }

    @BeforeEach
    void seed() {
        traceEventStore.store(StoredEvent.of(sendBoundToVoucher()));
        traceEventStore.store(StoredEvent.of(mintWithoutIssuer()));
        when(voucherLedgerService.fetchVoucher(VOUCHER_ID)).thenReturn(Optional.of(sampleVoucher()));
    }

    /** A voucher-bound SEND is listed by both the voucher inspect API and the trace endpoint. */
    @Test
    void shouldNavigateBetweenVoucherAndItsEvents() throws Exception {
        // Voucher inspect (not trace-gated) lists the event in transactionEvents
        mockMvc.perform(get("/api/v1/vouchers/{id}", VOUCHER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherId").value(VOUCHER_ID))
                .andExpect(jsonPath("$.transactionEvents[0].eventId").value(SEND_EVENT_ID))
                .andExpect(jsonPath("$.transactionEvents[0].kind").value("send"));

        // Trace endpoint (NIP-98 gated) lists the event referencing the voucher
        String url = "http://localhost/api/v1/trace/vouchers/" + VOUCHER_ID + "/events";
        mockMvc.perform(get("/api/v1/trace/vouchers/{id}/events", VOUCHER_ID)
                        .header("Authorization", nip98(url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].eventId").value(SEND_EVENT_ID))
                .andExpect(jsonPath("$.events[0].voucherRef").value(VOUCHER_ID));
    }

    /** An issuer-less MINT becomes discoverable by issuer after the voucher back-fills it. */
    @Test
    void shouldListIssuerEventsBackfilledFromVoucher() throws Exception {
        // Given: the voucher arrives and back-fills the issuer for the mint's proof
        new IssuerBackfill(sidecarIndex).backfill(VOUCHER_ID, ISSUER_ID, "pk-iss",
                List.of(new ProofIdentity(MINT, KEYSET, MINT_Y)));

        // When/Then: the issuer listing returns the event, annotated index_backfill
        String url = "http://localhost/api/v1/trace/issuers/" + ISSUER_ID + "/events";
        mockMvc.perform(get("/api/v1/trace/issuers/{id}/events", ISSUER_ID)
                        .header("Authorization", nip98(url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].eventId").value(MINT_EVENT_ID))
                .andExpect(content().string(containsString("\"issuerProvenance\":\"index_backfill\"")));
    }

    private static TransactionEvent sendBoundToVoucher() {
        ProofRef in = new ProofRef(64, KEYSET, SEND_Y, Optional.of("secret-send"),
                Optional.of("0288a1"), Optional.empty(), Optional.empty());
        return new TransactionEvent(
                Optional.of(SEND_EVENT_ID), "op-send-1", OperationKind.SEND, MINT, "sat",
                Instant.ofEpochMilli(1740000001000L), Instant.ofEpochSecond(1740000001L),
                "producerpk", Optional.empty(), List.of(in), List.of(), List.of(),
                Optional.empty(), Optional.of(VOUCHER_ID), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(SEND_EVENT_ID), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(1740000001L)));
    }

    private static TransactionEvent mintWithoutIssuer() {
        ProofRef out = new ProofRef(64, KEYSET, MINT_Y, Optional.of("secret-mint"),
                Optional.of("0288b2"), Optional.empty(), Optional.empty());
        return new TransactionEvent(
                Optional.of(MINT_EVENT_ID), "op-mint-1", OperationKind.MINT, MINT, "sat",
                Instant.ofEpochMilli(1740000002000L), Instant.ofEpochSecond(1740000002L),
                "producerpk", Optional.empty(), List.of(), List.of(out), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(MINT_EVENT_ID), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(1740000002L)));
    }

    private static VoucherNode sampleVoucher() {
        return new VoucherNode(
                VOUCHER_ID, "issuer-001", "pk-issuer", 1000L, 0, 1000L, 1000L, 1000L, "sat",
                BackingStrategy.PROPORTIONAL, BigDecimal.ONE, VoucherStatus.ISSUED,
                VoucherStateMetadata.empty(),
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-12-31T00:00:00Z"),
                "sample voucher", Map.of(), List.of(),
                new xyz.tcheeric.cashu.ledger.core.model.NostrEventMetadata(
                        "event-1", "pk-issuer", Instant.parse("2024-01-01T00:00:00Z"),
                        "wss://relay.imani.casa", 30078, List.of(), "sig-hex"));
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
        String id = CanonicalJson.eventId(FULL_PUB, createdAt, 27235, tags, "");
        byte[] sig = Schnorr.sign(HEX.parseHex(id), HEX.parseHex(FULL_PRIV), new byte[32]);
        ObjectMapper om = new ObjectMapper();
        ObjectNode ev = om.createObjectNode();
        ev.put("id", id);
        ev.put("pubkey", FULL_PUB);
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
