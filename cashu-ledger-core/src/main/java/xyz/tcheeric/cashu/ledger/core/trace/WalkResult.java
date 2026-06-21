package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.List;
import java.util.Optional;

/**
 * The result of a graph walk (design §5.5): the visited nodes, the edges between
 * them, and a truncation cursor when the node-count limit was hit.
 */
public record WalkResult(
        String direction,
        int depth,
        List<WalkNode> nodes,
        List<TraceEdge> edges,
        boolean truncated,
        Optional<String> cursor,
        int prunedCount
) {

    /** A node in the walk. */
    public record WalkNode(String eventId, String kind, String mintUrl, long transitionAtMs) {
    }
}
