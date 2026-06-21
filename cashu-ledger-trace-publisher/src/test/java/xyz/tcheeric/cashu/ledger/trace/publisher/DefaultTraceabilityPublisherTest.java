package xyz.tcheeric.cashu.ledger.trace.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRefRedactor;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.InMemoryOutboxStore;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxRecord;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxStore;

/**
 * Unit tests for {@link DefaultTraceabilityPublisher}: enqueue, idempotency,
 * validation, privacy redaction on the wire, and overflow-policy behaviour.
 */
class DefaultTraceabilityPublisherTest {

    private static final String PRIV_HEX =
            "0000000000000000000000000000000000000000000000000000000000000005";
    private static final byte[] REDACTION_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private final TraceEventSigner signer = new TraceEventSigner(PRIV_HEX);

    private static String y(String seed) {
        return "02" + seed.repeat(32);
    }

    private TransactionEvent swap(String operationId, PrivacyMode mode) {
        ProofRef in = new ProofRef(64, "00ad12ef", y("a1"),
                Optional.of("raw-secret-a1"), Optional.of("0288a1"), Optional.empty(), Optional.empty());
        ProofRef out = new ProofRef(64, "00ad12ef", y("c3"),
                Optional.of("raw-secret-c3"), Optional.of("0288c3"), Optional.empty(), Optional.empty());
        return new TransactionEvent(
                Optional.empty(), operationId, OperationKind.SWAP, "https://mint.imani.casa", "sat",
                Instant.ofEpochMilli(1740000000123L), Instant.ofEpochSecond(1740000000L),
                signer.publicKeyHex(), Optional.empty(), List.of(in), List.of(out), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), mode, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.empty(), NostrEventMetadata.TRACE_EVENT_KIND,
                        Optional.empty(), Optional.empty(), Instant.ofEpochSecond(1740000000L)));
    }

    private DefaultTraceabilityPublisher publisher(OutboxStore store, OverflowPolicy policy,
                                                   long capacity) {
        return new DefaultTraceabilityPublisher(store, signer, new ProofRefRedactor(REDACTION_KEY),
                policy, capacity, 0L, "key-2026", () -> 1_000L);
    }

    /** Tests that a valid event is signed and enqueued exactly once. */
    @Test
    void shouldEnqueueValidEvent() {
        // Arrange
        OutboxStore store = new InMemoryOutboxStore();
        TraceabilityPublisher publisher = publisher(store, OverflowPolicy.BLOCK_AND_ALERT, 100);

        // Act
        publisher.publish(swap("op-1", PrivacyMode.FULL));

        // Then
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(store.find("op-1")).isPresent();
    }

    /** Tests that publishing the same operation id twice results in one outbox row. */
    @Test
    void shouldBeIdempotentByOperationId() {
        // Arrange
        OutboxStore store = new InMemoryOutboxStore();
        TraceabilityPublisher publisher = publisher(store, OverflowPolicy.BLOCK_AND_ALERT, 100);

        // Act
        publisher.publish(swap("op-1", PrivacyMode.FULL));
        publisher.publish(swap("op-1", PrivacyMode.FULL));

        // Then
        assertThat(store.pendingCount()).isEqualTo(1);
    }

    /** Tests that an invalid event (unbalanced SWAP) is rejected before enqueue. */
    @Test
    void shouldRejectInvalidEvent() {
        // Arrange: outputs do not balance inputs
        OutboxStore store = new InMemoryOutboxStore();
        TraceabilityPublisher publisher = publisher(store, OverflowPolicy.BLOCK_AND_ALERT, 100);
        ProofRef in = new ProofRef(64, "00ad12ef", y("a1"),
                Optional.of("s"), Optional.of("c"), Optional.empty(), Optional.empty());
        ProofRef out = new ProofRef(100, "00ad12ef", y("c3"),
                Optional.of("s"), Optional.of("c"), Optional.empty(), Optional.empty());
        TransactionEvent bad = new TransactionEvent(
                Optional.empty(), "op-bad", OperationKind.SWAP, "https://mint.imani.casa", "sat",
                Instant.ofEpochMilli(1740000000123L), Instant.ofEpochSecond(1740000000L),
                signer.publicKeyHex(), Optional.empty(), List.of(in), List.of(out), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.empty(), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(1740000000L)));

        // Act / Then
        assertThatThrownBy(() -> publisher.publish(bad))
                .isInstanceOf(TraceabilityPublishException.class)
                .extracting(e -> ((TraceabilityPublishException) e).getCode())
                .isEqualTo("INVALID_OPERATION");
        assertThat(store.pendingCount()).isZero();
    }

    /** Tests that HASHED mode never writes a raw secret into the enqueued event JSON. */
    @Test
    void shouldRedactSecretsInHashedMode() {
        // Arrange
        OutboxStore store = new InMemoryOutboxStore();
        TraceabilityPublisher publisher = publisher(store, OverflowPolicy.BLOCK_AND_ALERT, 100);

        // Act
        publisher.publish(swap("op-1", PrivacyMode.HASHED));

        // Then: the raw secret string never appears; the privacy mode and key id do
        OutboxRecord row = store.find("op-1").orElseThrow();
        assertThat(row.eventJson()).doesNotContain("raw-secret-a1");
        assertThat(row.eventJson()).doesNotContain("raw-secret-c3");
        assertThat(row.eventJson()).contains("hashed");
        assertThat(row.eventJson()).contains("key-2026");
    }

    /** Tests that MINIMAL mode omits secret-bearing fields entirely from the JSON. */
    @Test
    void shouldOmitSecretsInMinimalMode() {
        // Arrange
        OutboxStore store = new InMemoryOutboxStore();
        TraceabilityPublisher publisher = publisher(store, OverflowPolicy.BLOCK_AND_ALERT, 100);

        // Act
        publisher.publish(swap("op-1", PrivacyMode.MINIMAL));

        // Then
        OutboxRecord row = store.find("op-1").orElseThrow();
        assertThat(row.eventJson()).doesNotContain("raw-secret-a1");
        assertThat(row.eventJson()).doesNotContain("\"secret\"");
        assertThat(row.eventJson()).contains("minimal");
    }

    /** Tests that BLOCK_AND_ALERT throws when the outbox stays full (block window 0). */
    @Test
    void shouldThrowWhenOutboxFullUnderBlockPolicy() {
        // Arrange: capacity 1, already full
        OutboxStore store = new InMemoryOutboxStore();
        store.enqueue(OutboxRecord.pending("filler", "evt-x", "{}", 0));
        TraceabilityPublisher publisher = publisher(store, OverflowPolicy.BLOCK_AND_ALERT, 1);

        // Act / Then
        assertThatThrownBy(() -> publisher.publish(swap("op-1", PrivacyMode.FULL)))
                .isInstanceOf(TraceabilityPublishException.class)
                .extracting(e -> ((TraceabilityPublishException) e).getCode())
                .isEqualTo("OUTBOX_FULL");
    }

    /** Tests that DROP_OLDEST evicts the oldest row to make room for the new one. */
    @Test
    void shouldDropOldestWhenConfigured() {
        // Arrange: capacity 1, one old filler row
        OutboxStore store = new InMemoryOutboxStore();
        store.enqueue(OutboxRecord.pending("filler", "evt-x", "{}", 0));
        TraceabilityPublisher publisher = publisher(store, OverflowPolicy.DROP_OLDEST_AND_ALERT, 1);

        // Act
        publisher.publish(swap("op-1", PrivacyMode.FULL));

        // Then: filler dropped, new row present
        assertThat(store.find("filler")).isEmpty();
        assertThat(store.find("op-1")).isPresent();
    }

    /** Tests that FAIL_OPEN accepts without enqueueing when full. */
    @Test
    void shouldAcceptWithoutEnqueueUnderFailOpen() {
        // Arrange
        OutboxStore store = new InMemoryOutboxStore();
        store.enqueue(OutboxRecord.pending("filler", "evt-x", "{}", 0));
        TraceabilityPublisher publisher = publisher(store, OverflowPolicy.FAIL_OPEN, 1);

        // Act
        publisher.publish(swap("op-1", PrivacyMode.FULL));

        // Then: nothing new enqueued, no exception
        assertThat(store.find("op-1")).isEmpty();
    }

    /** Tests that health reports outbox depth, capacity, and the active policy. */
    @Test
    void shouldReportHealth() {
        // Arrange
        OutboxStore store = new InMemoryOutboxStore();
        DefaultTraceabilityPublisher publisher = publisher(store, OverflowPolicy.BLOCK_AND_ALERT, 100);
        publisher.publish(swap("op-1", PrivacyMode.FULL));

        // Act
        PublisherHealth health = publisher.health();

        // Then
        assertThat(health.pendingCount()).isEqualTo(1);
        assertThat(health.capacity()).isEqualTo(100);
        assertThat(health.overflowPolicy()).isEqualTo(OverflowPolicy.BLOCK_AND_ALERT);
    }
}
