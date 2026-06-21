package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Property test: a downstream walk over a linear swap chain of length {@code n}
 * reaches exactly the {@code n + 1} events that are BFS-reachable from the root.
 */
class WalkPropertyTest {

    private static String y(int i) {
        return "02" + String.format("%064x", i).substring(0, 64);
    }

    @Property(tries = 50)
    void downstreamWalkReachesEveryChainedEvent(@ForAll @IntRange(min = 1, max = 25) int n) {
        SqliteSidecarIndex index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        try {
            IndexedTraceEventStore store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
            WalkService walk = new WalkService(store, new EdgeDeriver(store));

            // Root MINT produces proof y(0); each swap consumes y(i) and produces y(i+1).
            store(store, "e-0", OperationKind.MINT, 1000, List.of(), List.of(proof(y(0))));
            for (int i = 0; i < n; i++) {
                store(store, "e-" + (i + 1), OperationKind.SWAP, 2000 + i,
                        List.of(proof(y(i))), List.of(proof(y(i + 1))));
            }

            WalkResult result = walk.walkFromEvent("e-0", WalkService.Direction.DOWN, n + 5, n + 10);

            assertThat(result.nodes()).hasSize(n + 1);
            assertThat(result.truncated()).isFalse();
        } finally {
            index.close();
        }
    }

    private static ProofRef proof(String y) {
        return new ProofRef(64, "00ad12ef", y, Optional.of("s"), Optional.of("c"),
                Optional.empty(), Optional.empty());
    }

    private static void store(IndexedTraceEventStore store, String eventId, OperationKind kind, long ms,
                              List<ProofRef> in, List<ProofRef> out) {
        TransactionEvent e = new TransactionEvent(
                Optional.of(eventId), "op-" + eventId, kind, "https://mint.imani.casa", "sat",
                Instant.ofEpochMilli(ms), Instant.ofEpochSecond(ms / 1000), "pk",
                Optional.empty(), in, out, List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(0L), Optional.empty(), Optional.empty(), Optional.empty(),
                PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(eventId), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(ms / 1000)));
        store.store(StoredEvent.of(e));
    }
}
