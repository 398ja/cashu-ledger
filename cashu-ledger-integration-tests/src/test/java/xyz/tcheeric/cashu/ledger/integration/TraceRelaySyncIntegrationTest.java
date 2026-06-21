package xyz.tcheeric.cashu.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import xyz.tcheeric.cashu.ledger.core.trace.IndexedTraceEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.InMemoryRawEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.ProducerAttestationConfig;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex;
import xyz.tcheeric.cashu.ledger.core.trace.TraceEventMapper;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestService;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestValidator;
import xyz.tcheeric.cashu.ledger.core.trace.TraceQueryService;
import xyz.tcheeric.cashu.ledger.core.trace.TraceSyncEngine;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.SignedTraceEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceEventSigner;

/**
 * End-to-end integration test of the trace pipeline against a real Nostr relay
 * (Testcontainers). It is the authoritative relay-compatibility check: a relay that
 * validates BIP-340 signatures must accept our canonically-signed event, and the
 * sync engine must ingest it back into the ledger store (design §8.2 / T038).
 */
@Tag("integration")
@Testcontainers
class TraceRelaySyncIntegrationTest {

    private static final String PRIV =
            "0000000000000000000000000000000000000000000000000000000000000021";
    private static final String MINT = "https://mint.imani.casa";
    private static final long EVENT_MS = 1740000000123L;

    private static final int STRFRY_PORT = 7777;

    @Container
    private final GenericContainer<?> relay =
            new GenericContainer<>(DockerImageName.parse("dockurr/strfry:latest"))
                    .withExposedPorts(STRFRY_PORT)
                    // strfry needs a writable LMDB dir; also disable the whitelist
                    // write-policy plugin and the 1M open-files ulimit so the relay
                    // accepts arbitrary test events out of the box.
                    .withTmpFs(java.util.Map.of("/app/strfry-db", "rw"))
                    .withCommand("sh", "-c",
                            "sed -i -e 's|plugin = .*|plugin = \"\"|' -e 's|nofiles = .*|nofiles = 0|' "
                                    + "/etc/strfry.conf.default && exec /app/strfry.sh")
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(java.time.Duration.ofSeconds(60)));

    private SqliteSidecarIndex index;
    private TraceSyncEngine syncEngine;

    @AfterEach
    void tearDown() {
        if (syncEngine != null) {
            syncEngine.close();
        }
        if (index != null) {
            index.close();
        }
    }

    private static String y(String seed) {
        return "02" + seed.repeat(32);
    }

    private static ProofRef proof(long amount, String seed) {
        return new ProofRef(amount, "00ad12ef", y(seed),
                Optional.of("secret-" + seed), Optional.of("0288" + seed), Optional.empty(), Optional.empty());
    }

    /**
     * A real relay accepts our signed event (proving relay-compatible canonical
     * signing) and the sync engine ingests it so it becomes queryable.
     */
    @Test
    void shouldPublishToRelayAndSyncIntoLedger() throws Exception {
        // Arrange: ledger-side store, validator, ingest, and sync engine
        TraceEventSigner signer = new TraceEventSigner(PRIV);
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        IndexedTraceEventStore store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        TraceIngestValidator validator = new TraceIngestValidator(
                new ProducerAttestationConfig(Map.of(MINT, Set.of(signer.publicKeyHex()))),
                1, 60, Long.MAX_VALUE / 2, () -> EVENT_MS + 5000);
        TraceIngestService ingest = new TraceIngestService(new TraceEventMapper(), validator, store, false);
        TraceQueryService query = new TraceQueryService(store, index);

        SignedTraceEvent signed = signer.sign(swap(signer.publicKeyHex()));
        String wsUrl = "ws://" + relay.getHost() + ":" + relay.getMappedPort(STRFRY_PORT);

        syncEngine = new TraceSyncEngine(ingest);
        syncEngine.subscribe(wsUrl);

        // Act: publish the signed event to the relay as a raw EVENT frame.
        // The subscribe/ingest side uses nostr-java (TraceSyncEngine), but the
        // publish here uses a raw WebSocket deliberately: this test must transmit
        // OUR exact canonical bytes verbatim to prove the relay validates the
        // signature over our CanonicalJson serialisation. Re-encoding through a
        // nostr-java GenericEvent would test the library's bytes, not ours, and
        // would change the event id. When the production NostrRelayPublisher exists
        // (it must also send the pre-signed bytes verbatim to preserve idempotency),
        // this test will publish through it instead. (Option iii.)
        //
        // Retry the publish+ingest cycle a few times to absorb transient relay /
        // WebSocket timing under load. Re-publishing the same deterministic event
        // is idempotent, so retries are safe.
        String frame = "[\"EVENT\"," + signed.eventJson() + "]";
        boolean ingested = false;
        for (int attempt = 1; attempt <= 3 && !ingested; attempt++) {
            Optional<String> ok = publishEvent(wsUrl, frame, signed.eventId());
            ok.ifPresent(r -> assertThat(r).as("relay OK response").contains("true"));
            ingested = awaitEvent(query, signed.eventId(), 12_000);
        }

        // Then: ingestion proves the relay accepted our canonical signature and the
        // sync engine stored it; it is queryable as a SWAP.
        assertThat(ingested).as("event ingested into ledger store").isTrue();
        assertThat(query.getEvent(signed.eventId()).orElseThrow().event().kind())
                .isEqualTo(OperationKind.SWAP);
    }

    private Optional<String> publishEvent(String wsUrl, String frame, String eventId) throws Exception {
        CompletableFuture<String> okFuture = new CompletableFuture<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                        String message = data.toString();
                        if (message.contains("\"OK\"") && message.contains(eventId)) {
                            okFuture.complete(message);
                        }
                        socket.request(1);
                        return null;
                    }
                })
                .get(20, TimeUnit.SECONDS);
        try {
            ws.sendText(frame, true);
            try {
                return Optional.of(okFuture.get(30, TimeUnit.SECONDS));
            } catch (java.util.concurrent.TimeoutException e) {
                return Optional.empty();
            }
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    private boolean awaitEvent(TraceQueryService query, String eventId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (query.getEvent(eventId).isPresent()) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private TransactionEvent swap(String producerPubkey) {
        return new TransactionEvent(
                Optional.empty(), "op-it-1", OperationKind.SWAP, MINT, "sat",
                Instant.ofEpochMilli(EVENT_MS), Instant.ofEpochSecond(EVENT_MS / 1000),
                producerPubkey, Optional.empty(), List.of(proof(64, "a1")), List.of(proof(64, "c3")),
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.empty(), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(EVENT_MS / 1000)));
    }
}
