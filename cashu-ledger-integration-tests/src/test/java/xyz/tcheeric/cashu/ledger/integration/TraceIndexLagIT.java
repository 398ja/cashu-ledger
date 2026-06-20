package xyz.tcheeric.cashu.ledger.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import xyz.tcheeric.cashu.ledger.core.trace.RawEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.web.CashuLedgerWebApplication;

/**
 * Integration test for the index-lag guard (T030): when the raw store holds events the sidecar
 * has not yet projected, sidecar-derived reads return 503 INDEX_LAGGED with a Retry-After, while
 * a by-event-id read still serves directly from the system of record.
 */
@Tag("integration")
@SpringBootTest(classes = CashuLedgerWebApplication.class)
@AutoConfigureMockMvc
class TraceIndexLagIT {

    private static final HexFormat HEX = HexFormat.of();
    private static final String PRIV = "00000000000000000000000000000000000000000000000000000000000000c1";
    private static final String PUB = pub(PRIV);
    private static final String MINT = "https://mint.imani.casa";
    private static final String EVENT_ID = "evtindexlag000000000000000000000000000000000000000000000000ccc1";
    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RawEventStore rawEventStore;

    @MockBean
    private VoucherLedgerService voucherLedgerService;

    @DynamicPropertySource
    static void authorities(DynamicPropertyRegistry registry) {
        registry.add("trace.security.authorities[0].pubkey", () -> PUB);
        registry.add("trace.security.authorities[0].grants[0]", () -> "trace:read:full");
    }

    @BeforeEach
    void seedRawOnly() {
        // Write straight to the raw store, bypassing the sidecar: the index is now behind.
        rawEventStore.store(StoredEvent.of(event()));
    }

    /** A sidecar-derived listing is refused with 503 INDEX_LAGGED + Retry-After while the index lags. */
    @Test
    void shouldReturn503LaggedForSidecarReads() throws Exception {
        URI uri = URI.create("http://localhost/api/v1/trace/events");
        mockMvc.perform(get(uri).header("Authorization", nip98(uri.toString())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("INDEX_LAGGED"));
    }

    /** A by-event-id read still serves directly from the raw store despite the lag. */
    @Test
    void shouldStillServeByEventIdWhileLagged() throws Exception {
        URI uri = URI.create("http://localhost/api/v1/trace/events/" + EVENT_ID);
        mockMvc.perform(get(uri).header("Authorization", nip98(uri.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(EVENT_ID));
    }

    private static TransactionEvent event() {
        return new TransactionEvent(
                Optional.of(EVENT_ID), "op-lag", OperationKind.SWAP, MINT, "sat",
                Instant.ofEpochSecond(1740000000L), Instant.ofEpochSecond(1740000000L), "pk",
                Optional.empty(),
                List.of(new ProofRef(64, "00ad12ef", "02" + "a1".repeat(32), Optional.of("s"),
                        Optional.of("c"), Optional.empty(), Optional.empty())),
                List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(0L),
                Optional.empty(), Optional.empty(), Optional.empty(), PrivacyMode.FULL,
                Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(EVENT_ID), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(1740000000L)));
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
