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
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Unit tests for {@link WalkService}: downstream/upstream traversal of a mint→swap
 * →melt chain, deterministic re-runs, truncation, and double-consume edges.
 */
class WalkServiceTest {

    private static final String MINT = "https://mint.imani.casa";
    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;
    private WalkService walk;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        walk = new WalkService(store, new EdgeDeriver(store));
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

    private void store(String eventId, OperationKind kind, long ms,
                      List<ProofRef> inputs, List<ProofRef> outputs) {
        TransactionEvent e = new TransactionEvent(
                Optional.of(eventId), "op-" + eventId, kind, MINT, "sat",
                Instant.ofEpochMilli(ms), Instant.ofEpochSecond(ms / 1000), "pk",
                Optional.empty(), inputs, outputs, List.of(),
                kind == OperationKind.MELT
                        ? Optional.of(new xyz.tcheeric.cashu.ledger.trace.core.LightningRef(
                            "q", MINT, Optional.empty(), Optional.empty(), Optional.of(60L),
                            Optional.empty(), OperationKind.MELT_QUOTE_REQUESTED, false))
                        : Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(0L), Optional.empty(), Optional.empty(),
                Optional.empty(), PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(eventId), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(ms / 1000)));
        this.store.store(StoredEvent.of(e));
    }

    /** Builds mint(a,b) -> swap(a,b -> c,d) -> melt(d); swap(c) -> swap2(c -> f). */
    private void buildChain() {
        store("e-mint", OperationKind.MINT, 1000, List.of(), List.of(proof(64, "a1"), proof(64, "b2")));
        store("e-swap", OperationKind.SWAP, 2000,
                List.of(proof(64, "a1"), proof(64, "b2")), List.of(proof(64, "c3"), proof(64, "d4")));
        store("e-melt", OperationKind.MELT, 3000, List.of(proof(64, "d4")), List.of());
        store("e-swap2", OperationKind.SWAP, 4000, List.of(proof(64, "c3")), List.of(proof(64, "f6")));
    }

    private static List<String> nodeIds(WalkResult r) {
        return r.nodes().stream().map(WalkResult.WalkNode::eventId).toList();
    }

    /** Tests that a downstream walk from the mint reaches the whole chain. */
    @Test
    void shouldWalkDownstreamToTerminal() {
        // Arrange
        buildChain();

        // Act
        WalkResult result = walk.walkFromEvent("e-mint", WalkService.Direction.DOWN, 10, 100);

        // Then
        assertThat(nodeIds(result)).containsExactlyInAnyOrder("e-mint", "e-swap", "e-melt", "e-swap2");
    }

    /** Tests that an upstream walk from the melt reaches the swap and the originating mint. */
    @Test
    void shouldWalkUpstreamToRoot() {
        // Arrange
        buildChain();

        // Act
        WalkResult result = walk.walkFromEvent("e-melt", WalkService.Direction.UP, 10, 100);

        // Then
        assertThat(nodeIds(result)).containsExactlyInAnyOrder("e-melt", "e-swap", "e-mint");
    }

    /** Tests that repeating the same walk yields an identical node order (deterministic). */
    @Test
    void shouldBeDeterministic() {
        // Arrange
        buildChain();

        // Act
        WalkResult a = walk.walkFromEvent("e-mint", WalkService.Direction.DOWN, 10, 100);
        WalkResult b = walk.walkFromEvent("e-mint", WalkService.Direction.DOWN, 10, 100);

        // Then
        assertThat(nodeIds(a)).isEqualTo(nodeIds(b));
    }

    /** Tests that hitting the node-count limit truncates and returns a cursor. */
    @Test
    void shouldTruncateAtNodeLimit() {
        // Arrange
        buildChain();

        // Act
        WalkResult result = walk.walkFromEvent("e-mint", WalkService.Direction.DOWN, 10, 2);

        // Then
        assertThat(result.nodes()).hasSize(2);
        assertThat(result.truncated()).isTrue();
        assertThat(result.cursor()).isPresent();
    }

    /** Tests that two events consuming the same proof produce a double-consume spend edge. */
    @Test
    void shouldFlagDoubleConsume() {
        // Arrange: both melt and a second melt consume d4
        store("e-mint", OperationKind.MINT, 1000, List.of(), List.of(proof(64, "a1"), proof(64, "d4")));
        store("e-swap", OperationKind.SWAP, 2000,
                List.of(proof(64, "a1")), List.of(proof(64, "d4")));
        store("e-melt", OperationKind.MELT, 3000, List.of(proof(64, "d4")), List.of());
        store("e-melt2", OperationKind.MELT, 3500, List.of(proof(64, "d4")), List.of());

        // Act: downstream from the swap that produced d4
        WalkResult result = walk.walkFromEvent("e-swap", WalkService.Direction.DOWN, 5, 100);

        // Then
        assertThat(result.edges())
                .filteredOn(e -> e.role() == EdgeRole.SPEND && e.y().equals(Optional.of(y("d4"))))
                .allMatch(TraceEdge::doubleConsume)
                .hasSize(2);
    }
}
