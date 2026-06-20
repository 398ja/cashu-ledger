package xyz.tcheeric.cashu.ledger.e2e.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.ledger.core.trace.IndexedTraceEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.InMemoryRawEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.ProducerAttestationConfig;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex;
import xyz.tcheeric.cashu.ledger.core.trace.TraceEventMapper;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestService;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestValidator;
import xyz.tcheeric.cashu.ledger.core.trace.TraceSyncEngine;
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.NostrRelayPublisher;
import xyz.tcheeric.cashu.ledger.trace.publisher.RelayConfig;
import xyz.tcheeric.cashu.ledger.trace.publisher.RelayPublishResult;
import xyz.tcheeric.cashu.ledger.trace.publisher.SignedTraceEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceEventSigner;

/**
 * End-to-end proof that real mint operations are captured by the trace ledger (T039).
 *
 * <p>A real cashu-mint stack (mint, keyset vault, payment adapter, mock Lightning, strfry
 * relay) is started via Testcontainers. The test acts as the mint's traceability producer:
 * it mints and swaps <em>real</em> proofs through {@link MintWallet} (genuine BDHKE against
 * the live mint), turns each operation into a kind-9079 {@link TransactionEvent}, signs it
 * with {@link TraceEventSigner}, and publishes it to the relay with {@link NostrRelayPublisher}.
 * A {@link TraceSyncEngine} subscribed to the same relay drives the ledger's ingest pipeline,
 * and the test asserts both operations are captured within 30 seconds.</p>
 *
 * <p>Skipped cleanly when Docker is unavailable, so it never breaks {@code mvn verify}; it runs
 * under the {@code e2e-tests} profile.</p>
 */
@Tag("e2e")
class TraceCaptureE2ETest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String MINT_URL = "http://cashu-mint-rest:7777";
    private static final String UNIT = "sat";
    private static final int MINT_AMOUNT = 16;
    private static final File COMPOSE_FILE =
            new File("src/test/resources/trace-mint/docker-compose.yml");

    private static ComposeContainer stack;
    private static String mintBaseUrl;
    private static String relayWsUrl;

    @BeforeAll
    static void startStack() {
        assumeTrue(dockerAvailable(), "Docker is not available; skipping real-mint E2E");
        stack = new ComposeContainer(COMPOSE_FILE)
                .withLocalCompose(true)
                .withExposedService("cashu-mint-rest", 7777,
                        Wait.forHttp("/v1/info").forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(4)))
                .withExposedService("nostr-relay", 7777,
                        Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
        stack.start();

        mintBaseUrl = "http://" + stack.getServiceHost("cashu-mint-rest", 7777)
                + ":" + stack.getServicePort("cashu-mint-rest", 7777) + "/v1";
        relayWsUrl = "ws://" + stack.getServiceHost("nostr-relay", 7777)
                + ":" + stack.getServicePort("nostr-relay", 7777);
    }

    @AfterAll
    static void stopStack() {
        if (stack != null) {
            stack.stop();
        }
    }

    /**
     * Mints and swaps real proofs against the live mint, publishes a MINT and a SWAP trace
     * event built from those proofs, and verifies the ledger ingests exactly both within 30s.
     */
    @Test
    void shouldCaptureRealMintAndSwapOperations() throws Exception {
        TraceEventSigner signer = new TraceEventSigner(randomPrivateKeyHex());
        String producerPubkey = signer.publicKeyHex();

        SqliteSidecarIndex index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        IndexedTraceEventStore store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        TraceIngestValidator validator = new TraceIngestValidator(
                new ProducerAttestationConfig(Map.of(MINT_URL, Set.of(producerPubkey))),
                1, 60, 24 * 60 * 60, System::currentTimeMillis);
        TraceIngestService ingestService =
                new TraceIngestService(new TraceEventMapper(), validator, store, false);

        try (TraceSyncEngine syncEngine = new TraceSyncEngine(ingestService);
             NostrRelayPublisher publisher =
                     new NostrRelayPublisher(List.of(RelayConfig.privateRelay(relayWsUrl)), 10)) {
            syncEngine.subscribe(relayWsUrl);

            MintWallet wallet = new MintWallet(mintBaseUrl);
            MintWallet.Minted minted = wallet.mint(MINT_AMOUNT);
            List<Proof<RandomStringSecret>> swapped = wallet.swap(minted.proofs());

            publish(publisher, signer, mintEvent(producerPubkey, minted));
            publish(publisher, signer, swapEvent(producerPubkey, minted.proofs(), swapped));

            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> assertThat(ingestService.metrics().stored()).isEqualTo(2));
        } finally {
            index.close();
        }
    }

    private static void publish(NostrRelayPublisher publisher, TraceEventSigner signer,
                                TransactionEvent event) {
        SignedTraceEvent signed = signer.sign(event);
        RelayPublishResult result = publisher.publish(signed.eventJson(), signed.eventId());
        assertThat(result.deliveredToLedgerRelay())
                .as("relay should acknowledge %s (detail=%s)", event.kind(), result.detail())
                .isTrue();
    }

    private static TransactionEvent mintEvent(String producerPubkey, MintWallet.Minted minted) {
        Instant now = Instant.now();
        LightningRef lightning = new LightningRef(minted.quoteId(), MINT_URL,
                Optional.empty(), Optional.empty(), Optional.of((long) minted.amount()),
                Optional.empty(), OperationKind.MINT_QUOTE_REQUESTED, false);
        return event(producerPubkey, OperationKind.MINT, now, List.of(),
                proofRefs(minted.proofs()), Optional.of(lightning));
    }

    private static TransactionEvent swapEvent(String producerPubkey,
                                              List<Proof<RandomStringSecret>> inputs,
                                              List<Proof<RandomStringSecret>> outputs) {
        return event(producerPubkey, OperationKind.SWAP, Instant.now(),
                proofRefs(inputs), proofRefs(outputs), Optional.empty());
    }

    private static TransactionEvent event(String producerPubkey, OperationKind kind, Instant now,
                                          List<ProofRef> inputs, List<ProofRef> outputs,
                                          Optional<LightningRef> lightning) {
        Instant createdAt = Instant.ofEpochSecond(now.getEpochSecond());
        return new TransactionEvent(
                Optional.empty(), UUID.randomUUID().toString(), kind, MINT_URL, UNIT,
                now, createdAt, producerPubkey, Optional.empty(), inputs, outputs, List.of(),
                lightning, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(0L), Optional.empty(), Optional.empty(),
                Optional.empty(), PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.empty(), 9079, Optional.empty(), Optional.empty(),
                        createdAt));
    }

    private static List<ProofRef> proofRefs(List<Proof<RandomStringSecret>> proofs) {
        return proofs.stream().map(TraceCaptureE2ETest::proofRef).toList();
    }

    private static ProofRef proofRef(Proof<RandomStringSecret> proof) {
        String y = SecretUtil.toY(proof.getSecret());
        String c = HEX.formatHex(proof.getUnblindedSignature().getCompressedBytes());
        return new ProofRef(proof.getAmount(), proof.getKeySetId(), y,
                Optional.of(proof.getSecret().toString()), Optional.of(c),
                Optional.empty(), Optional.empty());
    }

    private static String randomPrivateKeyHex() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        key[0] |= 1; // keep it comfortably non-zero
        return HEX.formatHex(key);
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
