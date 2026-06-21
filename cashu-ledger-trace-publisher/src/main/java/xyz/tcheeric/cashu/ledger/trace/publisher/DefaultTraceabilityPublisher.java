package xyz.tcheeric.cashu.ledger.trace.publisher;

import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.OperationInvariants;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRefRedactor;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxRecord;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxStore;

/**
 * Default {@link TraceabilityPublisher}: validates, redacts to the event's privacy
 * mode, signs, and enqueues to the durable outbox — returning immediately in steady
 * state. When the outbox is at capacity the configured {@link OverflowPolicy}
 * applies (design §4.2 FR-11a).
 */
public final class DefaultTraceabilityPublisher implements TraceabilityPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTraceabilityPublisher.class);

    /** Default outbox capacity (rows). */
    public static final long DEFAULT_CAPACITY = 100_000L;
    /** Default bounded block under {@link OverflowPolicy#BLOCK_AND_ALERT} (ms). */
    public static final long DEFAULT_BLOCK_MILLIS = 250L;

    private final OutboxStore outbox;
    private final TraceEventSigner signer;
    private final ProofRefRedactor redactor;
    private final OverflowPolicy overflowPolicy;
    private final long capacity;
    private final long blockMillis;
    private final String redactionKeyId;
    private final LongSupplier clock;

    public DefaultTraceabilityPublisher(OutboxStore outbox, TraceEventSigner signer,
                                        ProofRefRedactor redactor, OverflowPolicy overflowPolicy,
                                        long capacity, long blockMillis, String redactionKeyId,
                                        LongSupplier clock) {
        this.outbox = outbox;
        this.signer = signer;
        this.redactor = redactor;
        this.overflowPolicy = overflowPolicy;
        this.capacity = capacity;
        this.blockMillis = blockMillis;
        this.redactionKeyId = redactionKeyId;
        this.clock = clock;
    }

    /** Convenience: BLOCK_AND_ALERT, default capacity/block, system clock, no HASHED key id. */
    public static DefaultTraceabilityPublisher create(OutboxStore outbox, TraceEventSigner signer,
                                                      ProofRefRedactor redactor) {
        return new DefaultTraceabilityPublisher(outbox, signer, redactor, OverflowPolicy.BLOCK_AND_ALERT,
                DEFAULT_CAPACITY, DEFAULT_BLOCK_MILLIS, null, System::currentTimeMillis);
    }

    @Override
    public void publish(TransactionEvent event) {
        List<OperationInvariants.Violation> violations = OperationInvariants.validate(event);
        if (!violations.isEmpty()) {
            OperationInvariants.Violation first = violations.get(0);
            throw new TraceabilityPublishException("INVALID_OPERATION",
                    "Event failed validation (" + first.code() + "): " + first.message());
        }
        TransactionEvent wire = toWireEvent(event);
        SignedTraceEvent signed = signer.sign(wire);
        enqueueWithOverflow(wire.operationId(), signed);
    }

    @Override
    public PublisherHealth health() {
        return new PublisherHealth(outbox.pendingCount(), capacity, overflowPolicy);
    }

    private void enqueueWithOverflow(String operationId, SignedTraceEvent signed) {
        long now = clock.getAsLong();
        OutboxRecord record = OutboxRecord.pending(operationId, signed.eventId(), signed.eventJson(), now);

        if (outbox.pendingCount() < capacity) {
            outbox.enqueue(record);
            return;
        }
        switch (overflowPolicy) {
            case BLOCK_AND_ALERT -> blockThenEnqueueOrThrow(record, now);
            case DROP_OLDEST_AND_ALERT -> {
                outbox.deleteOldestPending().ifPresent(dropped ->
                        LOGGER.error("trace_dropped policy=drop_oldest_and_alert dropped_operation_id={}", dropped));
                outbox.enqueue(record);
            }
            case DROP_NEW_AND_ALERT ->
                    LOGGER.error("trace_dropped policy=drop_new_and_alert operation_id={}", operationId);
            case FAIL_OPEN ->
                    LOGGER.error("trace_silently_dropped policy=fail_open operation_id={}", operationId);
        }
    }

    private void blockThenEnqueueOrThrow(OutboxRecord record, long startMs) {
        long deadline = startMs + blockMillis;
        while (clock.getAsLong() < deadline) {
            if (outbox.pendingCount() < capacity) {
                outbox.enqueue(record);
                return;
            }
            sleepBriefly();
        }
        if (outbox.pendingCount() < capacity) {
            outbox.enqueue(record);
            return;
        }
        throw new TraceabilityPublishException("OUTBOX_FULL",
                "Outbox at capacity (" + capacity + ") and did not drain within " + blockMillis
                        + "ms. Suggestion: scale ledger ingest, or choose a non-blocking overflow policy "
                        + "if availability outweighs audit completeness.");
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TraceabilityPublishException("INTERRUPTED",
                    "Interrupted while blocking on a full outbox", e);
        }
    }

    private TransactionEvent toWireEvent(TransactionEvent e) {
        PrivacyMode mode = e.privacyMode();
        List<ProofRef> inputs = e.inputs().stream().map(p -> redactor.redact(p, mode)).toList();
        List<ProofRef> outputs = e.outputs().stream().map(p -> redactor.redact(p, mode)).toList();
        Optional<LightningRef> lightning = e.lightning().map(l -> redactLightning(l, mode));
        Optional<String> redKeyId = mode == PrivacyMode.HASHED
                ? Optional.ofNullable(redactionKeyId) : Optional.empty();
        Optional<String> overflow = overflowPolicy.isLossy()
                ? Optional.of(overflowPolicy.wireValue()) : Optional.empty();

        return new TransactionEvent(
                Optional.empty(), e.operationId(), e.kind(), e.mintUrl(), e.unit(),
                e.transitionAt(), e.createdAt(), signer.publicKeyHex(), e.initiatorPubkey(),
                inputs, outputs, e.outputRoles(), lightning, e.voucherRef(), e.issuerId(),
                e.issuerPubkey(), e.bundleId(), e.transferId(), e.feeAmount(), e.errorCode(),
                e.errorMessage(), e.correctionOf(), mode, redKeyId, overflow,
                e.schemaVersion(), e.source());
    }

    private LightningRef redactLightning(LightningRef l, PrivacyMode mode) {
        return switch (mode) {
            case FULL -> l;
            case HASHED -> new LightningRef(l.quoteId(), l.mintUrl(),
                    l.bolt11().map(redactor::hmacHex), l.paymentHash(), l.amount(), l.expiresAt(),
                    l.quoteOperation(), l.partial());
            case MINIMAL -> new LightningRef(l.quoteId(), l.mintUrl(),
                    Optional.empty(), Optional.empty(), l.amount(), l.expiresAt(),
                    l.quoteOperation(), l.partial());
        };
    }
}
