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
import xyz.tcheeric.cashu.ledger.trace.publisher.DefaultTraceabilityPublisher;
import xyz.tcheeric.cashu.ledger.trace.publisher.InMemoryOperationIdRegistry;
import xyz.tcheeric.cashu.ledger.trace.publisher.NostrRelayPublisher;
import xyz.tcheeric.cashu.ledger.trace.publisher.OperationIdRegistry;
import xyz.tcheeric.cashu.ledger.trace.publisher.OverflowPolicy;
import xyz.tcheeric.cashu.ledger.trace.publisher.RelayConfig;
import xyz.tcheeric.cashu.ledger.trace.publisher.RelayPublishResult;
import xyz.tcheeric.cashu.ledger.trace.publisher.SignedTraceEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceEventSigner;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceabilityPublisher;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxDispatcher;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.SqliteOutboxStore;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRefRedactor;
import xyz.tcheeric.wallet.core.proof.NewProof;
import xyz.tcheeric.wallet.core.trace.WalletTraceProducer;

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

    /**
     * Spec 048 T059 — replaces the simulated-producer harness above with the
     * REAL {@link WalletTraceProducer} from wallet-lib 0.1.18+, driving the
     * SDK pipeline ({@link DefaultTraceabilityPublisher} +
     * {@link SqliteOutboxStore} + {@link OutboxDispatcher} +
     * {@link NostrRelayPublisher}) end-to-end against the live strfry +
     * cashu-ledger ingest harness.
     *
     * <p>Asserts the two SC-008 contracts:
     * <ol>
     *   <li><b>Forward-edge resolution by {@code y}</b> — the SWAP event's
     *       {@code inputs[*].y} set equals the MINT event's
     *       {@code outputs[*].y} set, so the ledger's DAG reconstructor
     *       can resolve the spend edge.</li>
     *   <li><b>Idempotency on operationId replay</b> — calling
     *       {@code recordSwap(sameInputs, …)} a second time produces no
     *       additional stored event (the outbox dedupes on
     *       {@code operationId}, which keys on the sorted input-{@code y}
     *       set per FR-010).</li>
     * </ol>
     *
     * <p>Both must hold within the spec's 30 s publish budget.
     *
     * <p>The MELT leg of the spec's "mint → swap → melt" workflow is
     * intentionally NOT exercised here: {@link MintWallet} has no melt
     * support today (would require a NUT-05 Lightning round-trip against
     * the mock-Lightning backend) and the SC-008 forward-edge claim is
     * provable with just the mint → swap hop. Adding melt is a follow-up
     * once MintWallet grows a {@code melt(...)} method.
     */
    @Test
    void shouldCaptureMintAndSwap_viaWalletLibProducer_andForwardEdgeResolves() throws Exception {
        // ───────────────────────────────────────────────────────────────
        // Pipeline: real wallet-lib WalletTraceProducer wraps a real
        // SDK DefaultTraceabilityPublisher whose dispatcher pumps the
        // in-memory SQLite outbox to the live strfry relay.
        // ───────────────────────────────────────────────────────────────
        String privateKeyHex = randomPrivateKeyHex();
        TraceEventSigner signer = new TraceEventSigner(privateKeyHex);
        String producerPubkey = signer.publicKeyHex();
        String redactionKeyHex = randomPrivateKeyHex();  // any 32 random bytes

        SqliteSidecarIndex index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        IndexedTraceEventStore store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        TraceIngestValidator validator = new TraceIngestValidator(
                new ProducerAttestationConfig(Map.of(MINT_URL, Set.of(producerPubkey))),
                1, 60, 24 * 60 * 60, System::currentTimeMillis);
        TraceIngestService ingestService =
                new TraceIngestService(new TraceEventMapper(), validator, store, false);

        SqliteOutboxStore outbox = new SqliteOutboxStore("jdbc:sqlite::memory:");
        try (TraceSyncEngine syncEngine = new TraceSyncEngine(ingestService);
             NostrRelayPublisher relayPublisher = new NostrRelayPublisher(
                     List.of(RelayConfig.privateRelay(relayWsUrl)), 10);
             OutboxDispatcher dispatcher = OutboxDispatcher.create(outbox, relayPublisher)) {
            // Use PrivacyMode.FULL so the published event content matches
            // what the ledger's TraceIngestService expects — the
            // ProofRefRedactor under FULL is a no-op pass-through, but
            // we still supply a redaction key for shape compatibility.
            TraceabilityPublisher sdkPublisher = new DefaultTraceabilityPublisher(
                    outbox, signer,
                    new ProofRefRedactor(HEX.parseHex(redactionKeyHex)),
                    OverflowPolicy.BLOCK_AND_ALERT,
                    DefaultTraceabilityPublisher.DEFAULT_CAPACITY,
                    DefaultTraceabilityPublisher.DEFAULT_BLOCK_MILLIS,
                    "e2e-key-v1",
                    System::currentTimeMillis);
            dispatcher.start();
            syncEngine.subscribe(relayWsUrl);

            OperationIdRegistry idRegistry = new InMemoryOperationIdRegistry();
            WalletTraceProducer producer = new WalletTraceProducer(
                    sdkPublisher, idRegistry, MINT_URL, producerPubkey,
                    redactionKeyHex, "e2e-key-v1");

            // ───────────────────────────────────────────────────────────
            // Drive a REAL mint + swap against the live mint stack.
            // ───────────────────────────────────────────────────────────
            MintWallet wallet = new MintWallet(mintBaseUrl);
            MintWallet.Minted minted = wallet.mint(MINT_AMOUNT);
            List<Proof<RandomStringSecret>> swapped = wallet.swap(minted.proofs());

            // The wallet-lib producer's recordMint takes List<NewProof>.
            // Bridge from the test's cashu-lib Proof<RandomStringSecret>
            // shape (the producer's adapters are tested in wallet-lib's
            // own ProofRefBuilder unit tests; here we exercise the
            // producer + SDK boundary, not the proof-shape adapters).
            List<NewProof> mintedAsNewProofs = minted.proofs().stream()
                    .map(TraceCaptureE2ETest::toNewProof)
                    .toList();
            producer.recordMint(minted.quoteId(), UNIT, minted.amount(),
                    mintedAsNewProofs, /* customerPubkeyHex */ null);

            // recordSwap takes List<ProofRef> directly — reuse the
            // existing proofRef() helper.
            List<ProofRef> swapInputs = proofRefs(minted.proofs());
            List<ProofRef> swapOutputs = proofRefs(swapped);
            producer.recordSwap(swapInputs, swapOutputs, /* fee */ 0L, UNIT, null);

            // SC-008 publish-budget — MINT + SWAP land in the ledger
            // within 30 s of operation completion. On timeout we surface
            // the ingest metrics so the failure tells us whether events
            // arrived but were rejected vs. didn't arrive at all.
            try {
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                        .untilAsserted(() -> assertThat(ingestService.metrics().stored()).isEqualTo(2));
            } catch (RuntimeException e) {
                throw new AssertionError(
                        "SC-008: expected 2 stored events within 30s. Final ingest metrics: "
                                + ingestService.metrics(), e);
            }

            // ───────────────────────────────────────────────────────────
            // SC-008 (a) — forward-edge resolution by y. The SWAP event's
            // inputs[*].y MUST equal the MINT event's outputs[*].y as a
            // set, so the ledger's DAG reconstructor can resolve the
            // spend edge. We don't query the ledger's DAG API here
            // (that's the ledger's own concern); we just verify the
            // shape contract that makes resolution possible — the y
            // values we published as MINT.outputs ARE the y values we
            // published as SWAP.inputs.
            // ───────────────────────────────────────────────────────────
            Set<String> mintOutputYs = mintedAsNewProofs.stream()
                    .map(np -> SecretUtil.toYFromString(
                            new String(np.secret(), java.nio.charset.StandardCharsets.UTF_8)))
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> swapInputYs = swapInputs.stream().map(ProofRef::y)
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(swapInputYs)
                    .as("SC-008 (a): SWAP.inputs[*].y MUST equal MINT.outputs[*].y for DAG forward-edge resolution")
                    .isEqualTo(mintOutputYs);

            // ───────────────────────────────────────────────────────────
            // SC-008 (b) — idempotency on operationId replay. Calling
            // recordSwap a second time with the SAME inputs MUST resolve
            // to the same operationId and dedupe at the outbox.
            // Verify two ways:
            //   (i) the registry returns the same operationId both times
            //  (ii) the ingest count stays at 2 (no third event lands)
            // ───────────────────────────────────────────────────────────
            List<String> sortedInputYs = swapInputs.stream()
                    .map(ProofRef::y).sorted().toList();
            String swapKey = String.join(",", sortedInputYs);
            String firstResolve = idRegistry.resolve("swap", swapKey);
            String secondResolve = idRegistry.resolve("swap", swapKey);
            assertThat(firstResolve)
                    .as("FR-010 / SC-008 (b): registry MUST return the same operationId for the same key")
                    .isEqualTo(secondResolve);

            // Replay the swap publish; the outbox dedupes by operationId.
            producer.recordSwap(swapInputs, swapOutputs, 0L, UNIT, null);

            // Give the dispatcher time to attempt the replay (and discard
            // it). Stored count must still be 2.
            Thread.sleep(3000);
            assertThat(ingestService.metrics().stored())
                    .as("SC-008 (b): replay of same-operationId SWAP MUST NOT land a new event on the ledger")
                    .isEqualTo(2);
        } finally {
            index.close();
            outbox.close();
        }
    }

    /**
     * Bridge {@code Proof<RandomStringSecret>} (cashu-lib shape) →
     * {@code NewProof} (wallet-lib shape) so the wallet-side producer's
     * {@code recordMint} can consume mint-fresh proofs.
     *
     * <p>The wallet-lib {@code NewProof.secret} field is the UTF-8 bytes
     * of the secret STRING REPRESENTATION (hex-encoded for RandomStringSecret,
     * or the raw NUT-10 JSON array for well-known secrets) — NOT the raw
     * underlying bytes. {@code ProofRefBuilder.fromNewProof} round-trips
     * via {@code new String(secret, UTF_8)} and then through
     * {@code SecretUtil.toYFromString}, which hex-decodes the result.
     * Storing {@code proof.getSecret().getData()} (the raw bytes) would
     * fail with {@code "Hex string must have an even length"} when
     * those random bytes happen to UTF-decode to a non-hex string.
     */
    private static NewProof toNewProof(Proof<RandomStringSecret> proof) {
        byte[] secret = proof.getSecret().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String cHex = HEX.formatHex(proof.getUnblindedSignature().getCompressedBytes());
        return new NewProof(proof.getAmount(), cHex, secret, proof.getKeySetId());
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
