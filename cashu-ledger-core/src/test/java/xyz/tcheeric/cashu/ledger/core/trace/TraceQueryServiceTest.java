package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventQuery;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Unit tests for {@link TraceQueryService}: cursor pagination across pages and
 * grouped (never merged) multi-mint proof history.
 */
class TraceQueryServiceTest {

    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;
    private TraceQueryService service;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        service = new TraceQueryService(store, index);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    private static String y(String seed) {
        return "02" + seed.repeat(32);
    }

    private static ProofRef proof(long amount, String seed) {
        return new ProofRef(amount, "00ad12ef", y(seed),
                Optional.of("s-" + seed), Optional.of("c-" + seed), Optional.empty(), Optional.empty());
    }

    private void storeSwap(String eventId, String mintUrl, long ms,
                          List<ProofRef> in, List<ProofRef> out) {
        TransactionEvent e = new TransactionEvent(
                Optional.of(eventId), "op-" + eventId, OperationKind.SWAP, mintUrl, "sat",
                Instant.ofEpochMilli(ms), Instant.ofEpochSecond(ms / 1000), "producer-pk",
                Optional.empty(), in, out, List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(0L), Optional.empty(), Optional.empty(), Optional.empty(),
                PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(eventId), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(ms / 1000)));
        store.store(StoredEvent.of(e));
    }

    private void storeMelt(String eventId, String mintUrl, long ms, List<ProofRef> in) {
        TransactionEvent e = new TransactionEvent(
                Optional.of(eventId), "op-" + eventId, OperationKind.MELT, mintUrl, "sat",
                Instant.ofEpochMilli(ms), Instant.ofEpochSecond(ms / 1000), "producer-pk",
                Optional.empty(), in, List.of(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(0L), Optional.empty(), Optional.empty(), Optional.empty(),
                PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(eventId), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(ms / 1000)));
        store.store(StoredEvent.of(e));
    }

    /** Tests that listing walks all events across pages via the cursor without overlap. */
    @Test
    void shouldPaginateWithCursor() {
        // Arrange: five events
        for (int i = 0; i < 5; i++) {
            storeSwap("evt-" + i, "https://mint.imani.casa", 1000 + i,
                    List.of(proof(64, String.format("%02d", i))),
                    List.of(proof(64, String.format("%02d", i + 50))));
        }

        // Act: page size 2
        EventPage first = service.listEvents(
                TraceEventQuery.builder().mintUrl("https://mint.imani.casa").limit(2).build());
        EventPage second = service.listEvents(
                TraceEventQuery.builder().mintUrl("https://mint.imani.casa").limit(2)
                        .cursor(first.nextCursor().orElseThrow()).build());
        EventPage third = service.listEvents(
                TraceEventQuery.builder().mintUrl("https://mint.imani.casa").limit(2)
                        .cursor(second.nextCursor().orElseThrow()).build());

        // Then: 2 + 2 + 1, distinct, no next cursor on the last page
        assertThat(first.events()).hasSize(2);
        assertThat(second.events()).hasSize(2);
        assertThat(third.events()).hasSize(1);
        assertThat(third.nextCursor()).isEmpty();
        assertThat(List.of(
                first.events().get(0).event().eventId().orElseThrow(),
                first.events().get(1).event().eventId().orElseThrow(),
                second.events().get(0).event().eventId().orElseThrow()))
                .doesNotHaveDuplicates();
    }

    /** Tests that a proof scoped to one mint/keyset returns its lifecycle and spent state. */
    @Test
    void shouldReturnScopedProofHistory() {
        // Arrange: c3 produced by a swap then consumed by a melt
        storeSwap("evt-swap", "https://mint.imani.casa", 1000,
                List.of(proof(64, "a1")), List.of(proof(64, "c3")));
        storeMelt("evt-melt", "https://mint.imani.casa", 2000, List.of(proof(64, "c3")));

        // Act
        ProofHistory history = service.proofHistory(
                Optional.of("https://mint.imani.casa"), Optional.of("00ad12ef"), y("c3"));

        // Then
        assertThat(history.groups()).hasSize(1);
        ProofHistory.Group group = history.groups().get(0);
        assertThat(group.originEventId()).contains("evt-swap");
        assertThat(group.terminalEventId()).contains("evt-melt");
        assertThat(group.currentlySpent()).isTrue();
        assertThat(group.events()).hasSize(2);
    }

    /** Tests that a bare y matching two mints returns grouped (not merged) history. */
    @Test
    void shouldGroupProofHistoryAcrossMints() {
        // Arrange: same y at two mints
        storeSwap("evt-a", "https://mint.imani.casa", 1000,
                List.of(proof(64, "a1")), List.of(proof(64, "c3")));
        storeSwap("evt-b", "https://mint.other.example", 1500,
                List.of(proof(64, "a1")), List.of(proof(64, "c3")));

        // Act
        ProofHistory history = service.proofHistory(Optional.empty(), Optional.empty(), y("c3"));

        // Then: two groups, one per mint, not a merged list
        assertThat(history.groups()).hasSize(2);
        assertThat(history.groups()).extracting(ProofHistory.Group::mintUrl)
                .containsExactlyInAnyOrder("https://mint.imani.casa", "https://mint.other.example");
    }

    /** Tests that y-candidate detection lists each distinct (mintUrl, keysetId). */
    @Test
    void shouldListYCandidates() {
        // Arrange
        storeSwap("evt-a", "https://mint.imani.casa", 1000,
                List.of(proof(64, "a1")), List.of(proof(64, "c3")));
        storeSwap("evt-b", "https://mint.other.example", 1500,
                List.of(proof(64, "a1")), List.of(proof(64, "c3")));

        // Act
        List<ProofCandidate> candidates = service.candidatesForY(y("c3"));

        // Then
        assertThat(candidates).hasSize(2);
    }
}
