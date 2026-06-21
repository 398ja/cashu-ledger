package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.EventActivity;
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventQuery;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Unit tests for {@link IndexedTraceEventStore}: storage, idempotency, proof-tuple
 * lookups (with multi-mint isolation), listing, and activity classification.
 */
class IndexedTraceEventStoreTest {

    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    private static String y(String seed) {
        return "02" + seed.repeat(32);
    }

    private static ProofRef proof(long amount, String ySeed) {
        return new ProofRef(amount, "00ad12ef", y(ySeed),
                Optional.of("s-" + ySeed), Optional.of("c-" + ySeed), Optional.empty(), Optional.empty());
    }

    private StoredEvent swap(String eventId, String mintUrl, long transitionMs,
                            List<ProofRef> inputs, List<ProofRef> outputs) {
        TransactionEvent e = new TransactionEvent(
                Optional.of(eventId), "op-" + eventId, OperationKind.SWAP, mintUrl, "sat",
                Instant.ofEpochMilli(transitionMs), Instant.ofEpochSecond(transitionMs / 1000),
                "producer-pk", Optional.empty(), inputs, outputs, List.of(),
                Optional.empty(), Optional.of("v-1"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(0L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(eventId), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(transitionMs / 1000)));
        return StoredEvent.of(e);
    }

    private StoredEvent melt(String eventId, String mintUrl, long transitionMs, List<ProofRef> inputs) {
        TransactionEvent e = new TransactionEvent(
                Optional.of(eventId), "op-" + eventId, OperationKind.MELT, mintUrl, "sat",
                Instant.ofEpochMilli(transitionMs), Instant.ofEpochSecond(transitionMs / 1000),
                "producer-pk", Optional.empty(), inputs, List.of(), List.of(),
                Optional.of(new LightningRef("q-1", mintUrl, Optional.empty(), Optional.empty(),
                        Optional.of(60L), Optional.empty(), OperationKind.MELT_QUOTE_REQUESTED, false)),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(2L), Optional.empty(),
                Optional.empty(), Optional.empty(), PrivacyMode.FULL, Optional.empty(),
                Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(eventId), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(transitionMs / 1000)));
        return StoredEvent.of(e);
    }

    /** Tests that a stored event is retrievable by id and operation id. */
    @Test
    void shouldStoreAndRetrieve() {
        // Arrange
        StoredEvent e = swap("evt-1", "https://mint.imani.casa", 1000,
                List.of(proof(64, "a1")), List.of(proof(64, "c3")));

        // Act
        boolean stored = store.store(e);

        // Then
        assertThat(stored).isTrue();
        assertThat(store.findByEventId("evt-1")).isPresent();
        assertThat(store.findByOperationId("op-evt-1")).isPresent();
    }

    /** Tests that storing the same event id twice is a no-op the second time. */
    @Test
    void shouldStoreIdempotently() {
        // Arrange
        StoredEvent e = swap("evt-1", "https://mint.imani.casa", 1000,
                List.of(proof(64, "a1")), List.of(proof(64, "c3")));

        // Act
        boolean first = store.store(e);
        boolean second = store.store(e);

        // Then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(store.getIndexStatus().indexedEventCount()).isEqualTo(1);
    }

    /** Tests that proof lookups find the producing and consuming events by tuple. */
    @Test
    void shouldFindByProofRef() {
        // Arrange: c3 is an output of the swap and an input of the melt
        store.store(swap("evt-swap", "https://mint.imani.casa", 1000,
                List.of(proof(64, "a1")), List.of(proof(64, "c3"))));
        store.store(melt("evt-melt", "https://mint.imani.casa", 2000, List.of(proof(64, "c3"))));

        // Act
        List<StoredEvent> asOutput = store.findByOutputProofRef("https://mint.imani.casa", "00ad12ef", y("c3"), 10);
        List<StoredEvent> asInput = store.findByInputProofRef("https://mint.imani.casa", "00ad12ef", y("c3"), 10);

        // Then
        assertThat(asOutput).extracting(s -> s.event().eventId().orElseThrow()).containsExactly("evt-swap");
        assertThat(asInput).extracting(s -> s.event().eventId().orElseThrow()).containsExactly("evt-melt");
    }

    /** Tests that the same y at two mints is isolated by the (mint_url, keyset_id, y) tuple. */
    @Test
    void shouldIsolateProofLookupAcrossMints() {
        // Arrange: same y at two different mints
        store.store(swap("evt-a", "https://mint.imani.casa", 1000,
                List.of(proof(64, "a1")), List.of(proof(64, "c3"))));
        store.store(swap("evt-b", "https://mint.other.example", 1500,
                List.of(proof(64, "a1")), List.of(proof(64, "c3"))));

        // Act
        List<StoredEvent> mintA = store.findByOutputProofRef("https://mint.imani.casa", "00ad12ef", y("c3"), 10);

        // Then: only the mint-A event, not mint-B
        assertThat(mintA).extracting(s -> s.event().eventId().orElseThrow()).containsExactly("evt-a");
    }

    /** Tests that mint-and-time listing returns matches newest-first. */
    @Test
    void shouldListByMintUrlNewestFirst() {
        // Arrange
        store.store(swap("evt-old", "https://mint.imani.casa", 1000,
                List.of(proof(64, "a1")), List.of(proof(64, "c3"))));
        store.store(swap("evt-new", "https://mint.imani.casa", 5000,
                List.of(proof(64, "b2")), List.of(proof(64, "d4"))));

        // Act
        List<StoredEvent> results = store.findByMintUrl("https://mint.imani.casa",
                Instant.ofEpochMilli(0), Instant.ofEpochMilli(10000), 10);

        // Then
        assertThat(results).extracting(s -> s.event().eventId().orElseThrow())
                .containsExactly("evt-new", "evt-old");
    }

    /** Tests that activity is terminal for a MELT and active for a SWAP. */
    @Test
    void shouldClassifyActivityByKind() {
        // Arrange
        store.store(swap("evt-swap", "https://mint.imani.casa", 1000,
                List.of(proof(64, "a1")), List.of(proof(64, "c3"))));
        store.store(melt("evt-melt", "https://mint.imani.casa", 2000, List.of(proof(64, "c3"))));

        // Then
        assertThat(store.getActivity("evt-swap")).isEqualTo(EventActivity.ACTIVE);
        assertThat(store.getActivity("evt-melt")).isEqualTo(EventActivity.TERMINAL);
    }

    /** Tests that the activity filter narrows listing results. */
    @Test
    void shouldFilterByActivity() {
        // Arrange
        store.store(swap("evt-swap", "https://mint.imani.casa", 1000,
                List.of(proof(64, "a1")), List.of(proof(64, "c3"))));
        store.store(melt("evt-melt", "https://mint.imani.casa", 2000, List.of(proof(64, "c3"))));

        // Act
        List<StoredEvent> terminal = store.findFiltered(
                TraceEventQuery.builder().activity(EventActivity.TERMINAL).build());

        // Then
        assertThat(terminal).extracting(s -> s.event().eventId().orElseThrow()).containsExactly("evt-melt");
    }

    /** Tests that voucher-ref listing returns events tagged with that voucher. */
    @Test
    void shouldListByVoucherRef() {
        // Arrange (swap fixture sets voucher_ref = v-1)
        store.store(swap("evt-1", "https://mint.imani.casa", 1000,
                List.of(proof(64, "a1")), List.of(proof(64, "c3"))));

        // Act
        List<StoredEvent> results = store.findByVoucherRef("v-1", 10);

        // Then
        assertThat(results).extracting(s -> s.event().eventId().orElseThrow()).containsExactly("evt-1");
    }
}
