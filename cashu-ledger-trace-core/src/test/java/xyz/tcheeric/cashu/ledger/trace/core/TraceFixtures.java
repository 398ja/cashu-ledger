package xyz.tcheeric.cashu.ledger.trace.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Test helpers for building {@link TransactionEvent} and {@link ProofRef}
 * instances without repeating the wide constructors in every test.
 */
final class TraceFixtures {

    static final String MINT_URL = "https://mint.imani.casa";
    static final String KEYSET = "00ad12ef";
    static final String PRODUCER = "a1b2c3";
    static final Instant TRANSITION_AT = Instant.ofEpochMilli(1740000000123L);
    static final Instant CREATED_AT = Instant.ofEpochSecond(1740000000L);

    private TraceFixtures() {
    }

    /** A 66-char lowercase-hex {@code y} derived from a two-char seed. */
    static String y(String seed) {
        return "02" + seed.repeat(32);
    }

    /** A FULL-mode proof with raw secret and C present. */
    static ProofRef fullProof(long amount, String ySeed) {
        return new ProofRef(amount, KEYSET, y(ySeed),
                Optional.of("secret-" + ySeed),
                Optional.of("0288" + ySeed),
                Optional.empty(),
                Optional.empty());
    }

    static NostrEventMetadata source() {
        return new NostrEventMetadata(Optional.empty(), NostrEventMetadata.TRACE_EVENT_KIND,
                Optional.empty(), Optional.empty(), CREATED_AT);
    }

    static EventBuilder event() {
        return new EventBuilder();
    }

    /** A valid SWAP fixture: 64+64 in, 64+64 out, fee 0. */
    static TransactionEvent validSwap() {
        return event()
                .kind(OperationKind.SWAP)
                .inputs(List.of(fullProof(64, "a1"), fullProof(64, "b2")))
                .outputs(List.of(fullProof(64, "c3"), fullProof(64, "d4")))
                .feeAmount(0L)
                .build();
    }

    /** A valid MINT fixture: no inputs, 128 out, quote amount 128. */
    static TransactionEvent validMint() {
        return event()
                .kind(OperationKind.MINT)
                .outputs(List.of(fullProof(64, "a1"), fullProof(64, "b2")))
                .lightning(new LightningRef("q-mint", MINT_URL, Optional.empty(), Optional.empty(),
                        Optional.of(128L), Optional.empty(),
                        OperationKind.MINT_QUOTE_REQUESTED, false))
                .build();
    }

    /** Mutable builder mirroring {@link TransactionEvent} with valid defaults. */
    static final class EventBuilder {
        private String operationId = "0192f70a-7b3c-7c6e-8a40-1a2b3c4d5e6f";
        private OperationKind kind = OperationKind.SWAP;
        private String mintUrl = MINT_URL;
        private String unit = "sat";
        private Instant transitionAt = TRANSITION_AT;
        private Instant createdAt = CREATED_AT;
        private String producerPubkey = PRODUCER;
        private Optional<String> initiatorPubkey = Optional.empty();
        private List<ProofRef> inputs = new ArrayList<>();
        private List<ProofRef> outputs = new ArrayList<>();
        private List<OutputRole> outputRoles = new ArrayList<>();
        private Optional<LightningRef> lightning = Optional.empty();
        private Optional<String> voucherRef = Optional.empty();
        private Optional<String> issuerId = Optional.empty();
        private Optional<String> issuerPubkey = Optional.empty();
        private Optional<String> bundleId = Optional.empty();
        private Optional<String> transferId = Optional.empty();
        private Optional<Long> feeAmount = Optional.empty();
        private Optional<String> errorCode = Optional.empty();
        private Optional<String> errorMessage = Optional.empty();
        private Optional<String> correctionOf = Optional.empty();
        private PrivacyMode privacyMode = PrivacyMode.FULL;
        private Optional<String> redactionKeyId = Optional.empty();
        private Optional<String> overflowPolicy = Optional.empty();
        private int schemaVersion = TransactionEvent.CURRENT_SCHEMA_VERSION;

        EventBuilder operationId(String v) { this.operationId = v; return this; }
        EventBuilder kind(OperationKind v) { this.kind = v; return this; }
        EventBuilder mintUrl(String v) { this.mintUrl = v; return this; }
        EventBuilder unit(String v) { this.unit = v; return this; }
        EventBuilder transitionAt(Instant v) { this.transitionAt = v; return this; }
        EventBuilder createdAt(Instant v) { this.createdAt = v; return this; }
        EventBuilder producerPubkey(String v) { this.producerPubkey = v; return this; }
        EventBuilder initiatorPubkey(String v) { this.initiatorPubkey = Optional.ofNullable(v); return this; }
        EventBuilder inputs(List<ProofRef> v) { this.inputs = v; return this; }
        EventBuilder outputs(List<ProofRef> v) { this.outputs = v; return this; }
        EventBuilder outputRoles(List<OutputRole> v) { this.outputRoles = v; return this; }
        EventBuilder lightning(LightningRef v) { this.lightning = Optional.ofNullable(v); return this; }
        EventBuilder voucherRef(String v) { this.voucherRef = Optional.ofNullable(v); return this; }
        EventBuilder issuerId(String v) { this.issuerId = Optional.ofNullable(v); return this; }
        EventBuilder issuerPubkey(String v) { this.issuerPubkey = Optional.ofNullable(v); return this; }
        EventBuilder bundleId(String v) { this.bundleId = Optional.ofNullable(v); return this; }
        EventBuilder transferId(String v) { this.transferId = Optional.ofNullable(v); return this; }
        EventBuilder feeAmount(Long v) { this.feeAmount = Optional.ofNullable(v); return this; }
        EventBuilder errorCode(String v) { this.errorCode = Optional.ofNullable(v); return this; }
        EventBuilder errorMessage(String v) { this.errorMessage = Optional.ofNullable(v); return this; }
        EventBuilder privacyMode(PrivacyMode v) { this.privacyMode = v; return this; }
        EventBuilder redactionKeyId(String v) { this.redactionKeyId = Optional.ofNullable(v); return this; }
        EventBuilder overflowPolicy(String v) { this.overflowPolicy = Optional.ofNullable(v); return this; }

        TransactionEvent build() {
            return new TransactionEvent(
                    Optional.empty(), operationId, kind, mintUrl, unit, transitionAt, createdAt,
                    producerPubkey, initiatorPubkey, inputs, outputs, outputRoles, lightning,
                    voucherRef, issuerId, issuerPubkey, bundleId, transferId, feeAmount,
                    errorCode, errorMessage, correctionOf, privacyMode, redactionKeyId,
                    overflowPolicy, schemaVersion, source());
        }
    }
}
