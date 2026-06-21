package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.tcheeric.cashu.ledger.core.trace.VisualisationGraph.MintSubGraph;
import xyz.tcheeric.cashu.ledger.core.trace.VisualisationGraph.TransferLink;
import xyz.tcheeric.cashu.ledger.core.trace.VisualisationGraph.VisNode;
import xyz.tcheeric.cashu.ledger.core.trace.VisualisationGraph.VisEdge;
import xyz.tcheeric.cashu.ledger.core.trace.WalkResult.WalkNode;

/**
 * Transforms a {@link WalkResult} into a per-mint {@link VisualisationGraph} for the
 * graph UI (design §5.3.2). Intra-mint edges stay inside their mint's sub-graph; cross-mint
 * {@code transfer} edges become overlay links, flagged when their counterpart node is absent
 * from the walked set. The walk already carries only secret-free summaries, so the resulting
 * payload is safe for the visualisation surface.
 */
public final class VisualisationService {

    /** Groups a walk result into per-mint sub-graphs with a cross-mint transfer overlay. */
    public VisualisationGraph fromWalk(WalkResult walk) {
        Map<String, WalkNode> byId = new LinkedHashMap<>();
        Map<String, List<VisNode>> nodesByMint = new LinkedHashMap<>();
        for (WalkNode node : walk.nodes()) {
            byId.put(node.eventId(), node);
            nodesByMint.computeIfAbsent(node.mintUrl(), key -> new ArrayList<>())
                    .add(new VisNode(node.eventId(), node.kind(), node.transitionAtMs()));
        }

        Map<String, List<VisEdge>> edgesByMint = new LinkedHashMap<>();
        List<TransferLink> transfers = new ArrayList<>();
        for (TraceEdge edge : walk.edges()) {
            if (edge.role() == EdgeRole.TRANSFER) {
                transfers.add(toTransferLink(edge, byId));
            } else {
                placeIntraMintEdge(edge, byId, edgesByMint);
            }
        }

        Set<String> mintUrls = new LinkedHashSet<>(nodesByMint.keySet());
        mintUrls.addAll(edgesByMint.keySet());
        List<MintSubGraph> subGraphs = new ArrayList<>(mintUrls.size());
        for (String mintUrl : mintUrls) {
            subGraphs.add(new MintSubGraph(mintUrl,
                    nodesByMint.getOrDefault(mintUrl, List.of()),
                    edgesByMint.getOrDefault(mintUrl, List.of())));
        }
        return new VisualisationGraph(subGraphs, transfers,
                walk.truncated(), walk.cursor(), walk.prunedCount());
    }

    private static TransferLink toTransferLink(TraceEdge edge, Map<String, WalkNode> byId) {
        WalkNode from = byId.get(edge.fromEventId());
        WalkNode to = byId.get(edge.toEventId());
        return new TransferLink(
                edge.fromEventId(), edge.toEventId(),
                from != null ? from.mintUrl() : null,
                to != null ? to.mintUrl() : null,
                edge.transferId().orElse(null),
                to == null);
    }

    private static void placeIntraMintEdge(TraceEdge edge, Map<String, WalkNode> byId,
                                           Map<String, List<VisEdge>> edgesByMint) {
        String mintUrl = mintOf(edge.fromEventId(), byId);
        if (mintUrl == null) {
            mintUrl = mintOf(edge.toEventId(), byId);
        }
        if (mintUrl == null) {
            return;
        }
        edgesByMint.computeIfAbsent(mintUrl, key -> new ArrayList<>())
                .add(new VisEdge(edge.fromEventId(), edge.toEventId(),
                        edge.role().wireValue(), edge.amount().orElse(null), edge.doubleConsume()));
    }

    private static String mintOf(String eventId, Map<String, WalkNode> byId) {
        WalkNode node = byId.get(eventId);
        return node != null ? node.mintUrl() : null;
    }
}
