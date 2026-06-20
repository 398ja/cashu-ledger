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
 * Tests the minimal tombstone behaviour (SC-011): pruning an event removes its raw payload but
 * keeps the hop traversable as a counted placeholder, and the walk stops expanding past it.
 */
class TombstoneWalkTest {

    private static final String MINT = "https://mint.imani.casa";

    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;
    private TombstoneStore tombstones;
    private WalkService walk;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        tombstones = new TombstoneStore(store);
        walk = new WalkService(store, new EdgeDeriver(store), index::isTombstoned);
        // mint(y0) -> swap consumes y0, produces y1 -> melt consumes y1
        store.store(StoredEvent.of(event("e-mint", OperationKind.MINT, 1000, List.of(), List.of(proof(0)))));
        store.store(StoredEvent.of(event("e-swap", OperationKind.SWAP, 2000, List.of(proof(0)), List.of(proof(1)))));
        store.store(StoredEvent.of(event("e-melt", OperationKind.MELT, 3000, List.of(proof(1)), List.of())));
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    /** Pruning removes the raw event but the hop is still reachable as a counted tombstone. */
    @Test
    void shouldRenderPrunedHopAsCountedTombstoneAndStopExpansion() {
        // Given: the swap hop is pruned
        boolean removed = tombstones.prune("e-swap");
        assertThat(removed).isTrue();
        assertThat(index.tombstoneCount()).isEqualTo(1);
        assertThat(store.findByEventId("e-swap").flatMap(StoredEvent::rawEventJson)).isEmpty();

        // When: walking downstream from the mint
        WalkResult result = walk.walkFromEvent("e-mint", WalkService.Direction.DOWN, 10, 100);

        // Then: mint and the swap tombstone are present, prunedCount records the hop, and the
        // walk does not expand past the tombstone to the melt
        assertThat(result.prunedCount()).isEqualTo(1);
        assertThat(result.nodes()).extracting(WalkResult.WalkNode::eventId)
                .containsExactlyInAnyOrder("e-mint", "e-swap");
        assertThat(result.nodes()).extracting(WalkResult.WalkNode::eventId).doesNotContain("e-melt");
    }

    private static ProofRef proof(int i) {
        return new ProofRef(64, "00ad12ef", "02" + String.format("%062x", i),
                Optional.of("s" + i), Optional.of("c" + i), Optional.empty(), Optional.empty());
    }

    private static TransactionEvent event(String id, OperationKind kind, long ms,
                                          List<ProofRef> in, List<ProofRef> out) {
        return new TransactionEvent(
                Optional.of(id), "op-" + id, kind, MINT, "sat",
                Instant.ofEpochMilli(ms), Instant.ofEpochSecond(ms / 1000), "pk",
                Optional.empty(), in, out, List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(0L), Optional.empty(), Optional.empty(), Optional.empty(),
                PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(id), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(ms / 1000)));
    }
}
