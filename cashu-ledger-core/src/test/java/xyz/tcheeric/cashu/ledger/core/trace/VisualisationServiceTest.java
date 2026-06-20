package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.core.trace.VisualisationGraph.MintSubGraph;
import xyz.tcheeric.cashu.ledger.core.trace.VisualisationGraph.TransferLink;
import xyz.tcheeric.cashu.ledger.core.trace.WalkResult.WalkNode;

/**
 * Unit tests for {@link VisualisationService}: grouping a walk into per-mint sub-graphs and
 * lifting cross-mint transfer edges into the overlay with counterpart-missing detection.
 */
class VisualisationServiceTest {

    private static final String MINT_A = "https://mint-a.example";
    private static final String MINT_B = "https://mint-b.example";

    private final VisualisationService service = new VisualisationService();

    /** Intra-mint spend edges stay in their mint's sub-graph; transfer edges go to the overlay. */
    @Test
    void shouldGroupNodesPerMintAndLiftTransferEdges() {
        // Given: two mint-A events linked by a spend, plus a mint-B event linked by transfer
        WalkResult walk = new WalkResult("down", 5,
                List.of(node("a1", MINT_A), node("a2", MINT_A), node("b1", MINT_B)),
                List.of(
                        TraceEdge.spend("a1", "a2", "ks", "02ab", 8, false, List.of()),
                        TraceEdge.transfer("a2", "b1", "tx-1")),
                false, Optional.empty(), 0);

        // When: building the visualisation graph
        VisualisationGraph graph = service.fromWalk(walk);

        // Then: two sub-graphs; the spend lives under mint A; the transfer is an overlay link
        assertThat(graph.mints()).extracting(MintSubGraph::mintUrl)
                .containsExactly(MINT_A, MINT_B);
        MintSubGraph mintA = graph.mints().get(0);
        assertThat(mintA.nodes()).hasSize(2);
        assertThat(mintA.edges()).singleElement()
                .satisfies(e -> assertThat(e.role()).isEqualTo("spend"));
        assertThat(graph.transfers()).singleElement()
                .satisfies(t -> {
                    assertThat(t.fromMintUrl()).isEqualTo(MINT_A);
                    assertThat(t.toMintUrl()).isEqualTo(MINT_B);
                    assertThat(t.counterpartMissing()).isFalse();
                });
    }

    /** A transfer whose destination node is absent is flagged counterpart-missing. */
    @Test
    void shouldFlagTransferWhenCounterpartNotInGraph() {
        // Given: a transfer pointing to an event that was not walked
        WalkResult walk = new WalkResult("down", 5,
                List.of(node("a1", MINT_A)),
                List.of(TraceEdge.transfer("a1", "b-missing", "tx-9")),
                false, Optional.empty(), 0);

        // When: building the graph
        VisualisationGraph graph = service.fromWalk(walk);

        // Then: the overlay link is flagged and its destination mint is unknown
        TransferLink link = graph.transfers().get(0);
        assertThat(link.counterpartMissing()).isTrue();
        assertThat(link.toMintUrl()).isNull();
    }

    private static WalkNode node(String eventId, String mintUrl) {
        return new WalkNode(eventId, "swap", mintUrl, 1000L);
    }
}
