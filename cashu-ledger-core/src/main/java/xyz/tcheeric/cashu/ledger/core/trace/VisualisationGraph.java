package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.List;
import java.util.Optional;

/**
 * A secret-free graph payload for the visualisation UI (design §5.3.2 / §5.5, FR-036).
 * Nodes are grouped into per-mint sub-graphs; cross-mint {@code transfer} relationships are
 * lifted into a separate overlay so the UI can render them as dashed links. No field carries
 * a proof secret, signature, or Lightning payload.
 *
 * @param mints       per-mint sub-graphs, in first-seen order
 * @param transfers   cross-mint transfer overlay edges
 * @param truncated   whether the underlying walk hit its node/depth bound
 * @param cursor      continuation cursor when truncated
 * @param prunedCount number of pruned (tombstone) hops encountered
 */
public record VisualisationGraph(
        List<MintSubGraph> mints,
        List<TransferLink> transfers,
        boolean truncated,
        Optional<String> cursor,
        int prunedCount) {

    /** A single mint's nodes and the intra-mint edges between them. */
    public record MintSubGraph(String mintUrl, List<VisNode> nodes, List<VisEdge> edges) {
    }

    /** A graph node: an event summary, secret-free. */
    public record VisNode(String eventId, String kind, long transitionAtMs) {
    }

    /** An intra-mint directed edge with an optional amount label. */
    public record VisEdge(String fromEventId, String toEventId, String role,
                          Long amount, boolean doubleConsume) {
    }

    /**
     * A cross-mint transfer link. {@code counterpartMissing} is set when the destination
     * event is not present in the rendered graph (the counterpart was not walked or is
     * pruned), so the UI can flag a dangling transfer.
     */
    public record TransferLink(String fromEventId, String toEventId, String fromMintUrl,
                               String toMintUrl, String transferId, boolean counterpartMissing) {
    }
}
