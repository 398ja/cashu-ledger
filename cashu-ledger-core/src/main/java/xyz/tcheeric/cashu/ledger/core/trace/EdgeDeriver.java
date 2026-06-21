package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.ArrayList;
import java.util.List;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Derives the graph edges incident to an event by matching proof tuples and
 * correlation tags against the store (design §5.5 / FR-005/6/8/9/27). Edges are
 * computed lazily; nothing is persisted.
 *
 * <ul>
 *   <li><b>spend</b>: a producer output {@code (mint_url, keyset_id, y)} consumed by
 *       a downstream SWAP/MELT input; flagged {@code doubleConsume} when more than
 *       one event consumes the same proof.</li>
 *   <li><b>quote</b>: a quote-only event ↔ its settlement (matching quote id).</li>
 *   <li><b>possession</b>: SEND ↔ RECEIVE sharing a bundle id.</li>
 *   <li><b>transfer</b>: cross-mint events sharing a transfer id.</li>
 *   <li><b>attempt</b>: a failed melt's input ← the event that produced it.</li>
 * </ul>
 */
public final class EdgeDeriver {

    private static final int NEIGHBOUR_LIMIT = 64;

    private final TraceEventStore store;

    public EdgeDeriver(TraceEventStore store) {
        this.store = store;
    }

    /** Edges where {@code e} is the upstream (from) node — followed when walking downstream. */
    public List<TraceEdge> outgoing(StoredEvent e) {
        TransactionEvent event = e.event();
        String eventId = event.eventId().orElseThrow();
        List<TraceEdge> edges = new ArrayList<>();

        if (event.kind().isEdgeProducer()) {
            for (ProofRef out : event.outputs()) {
                List<StoredEvent> consumers = store
                        .findByInputProofRef(event.mintUrl(), out.keysetId(), out.y(), NEIGHBOUR_LIMIT)
                        .stream().filter(c -> !isSame(c, eventId)).toList();
                List<String> conflicting = consumers.stream()
                        .map(c -> c.event().eventId().orElseThrow()).toList();
                boolean doubleConsume = consumers.size() > 1;
                for (StoredEvent c : consumers) {
                    edges.add(TraceEdge.spend(eventId, c.event().eventId().orElseThrow(),
                            out.keysetId(), out.y(), out.amount(), doubleConsume, conflicting));
                }
            }
        }
        if (event.kind() == OperationKind.SEND) {
            event.bundleId().ifPresent(bundleId -> store.findByBundleId(bundleId, NEIGHBOUR_LIMIT).stream()
                    .filter(b -> b.event().kind() == OperationKind.RECEIVE && !isSame(b, eventId))
                    .forEach(b -> edges.add(
                            TraceEdge.possession(eventId, b.event().eventId().orElseThrow(), bundleId))));
        }
        if (event.kind().isQuoteRequest()) {
            event.lightning().ifPresent(ln -> store.findByQuote(event.mintUrl(), ln.quoteId(), NEIGHBOUR_LIMIT)
                    .stream().filter(s -> isSettlement(s) && !isSame(s, eventId))
                    .forEach(s -> edges.add(
                            TraceEdge.quote(eventId, s.event().eventId().orElseThrow(), ln.quoteId()))));
        }
        addTransferEdges(event, eventId, edges);
        return edges;
    }

    /** Edges where {@code e} is the downstream (to) node — followed when walking upstream. */
    public List<TraceEdge> incoming(StoredEvent e) {
        TransactionEvent event = e.event();
        String eventId = event.eventId().orElseThrow();
        List<TraceEdge> edges = new ArrayList<>();

        boolean consumer = event.kind().isEdgeConsumer();
        boolean failed = event.kind() == OperationKind.MELT_FAILED || event.kind() == OperationKind.MINT_FAILED;
        if (consumer || failed) {
            for (ProofRef in : event.inputs()) {
                for (StoredEvent producer : store.findByOutputProofRef(
                        event.mintUrl(), in.keysetId(), in.y(), NEIGHBOUR_LIMIT)) {
                    if (isSame(producer, eventId)) {
                        continue;
                    }
                    String producerId = producer.event().eventId().orElseThrow();
                    edges.add(consumer
                            ? TraceEdge.spend(producerId, eventId, in.keysetId(), in.y(), in.amount(), false, List.of())
                            : TraceEdge.attempt(producerId, eventId, in.keysetId(), in.y()));
                }
            }
        }
        if (isSettlement(e)) {
            event.lightning().ifPresent(ln -> store.findByQuote(event.mintUrl(), ln.quoteId(), NEIGHBOUR_LIMIT)
                    .stream().filter(q -> q.event().kind().isQuoteRequest() && !isSame(q, eventId))
                    .forEach(q -> edges.add(
                            TraceEdge.quote(q.event().eventId().orElseThrow(), eventId, ln.quoteId()))));
        }
        if (event.kind() == OperationKind.RECEIVE) {
            event.bundleId().ifPresent(bundleId -> store.findByBundleId(bundleId, NEIGHBOUR_LIMIT).stream()
                    .filter(s -> s.event().kind() == OperationKind.SEND && !isSame(s, eventId))
                    .forEach(s -> edges.add(
                            TraceEdge.possession(s.event().eventId().orElseThrow(), eventId, bundleId))));
        }
        addTransferEdges(event, eventId, edges);
        return edges;
    }

    private void addTransferEdges(TransactionEvent event, String eventId, List<TraceEdge> edges) {
        event.transferId().ifPresent(transferId -> store.findByTransferId(transferId, NEIGHBOUR_LIMIT).stream()
                .filter(o -> !isSame(o, eventId) && !o.event().mintUrl().equals(event.mintUrl()))
                .forEach(o -> edges.add(
                        TraceEdge.transfer(eventId, o.event().eventId().orElseThrow(), transferId))));
    }

    private static boolean isSettlement(StoredEvent e) {
        return switch (e.event().kind()) {
            case MINT, MELT, MINT_FAILED, MELT_FAILED -> true;
            default -> false;
        };
    }

    private static boolean isSame(StoredEvent e, String eventId) {
        return e.event().eventId().map(eventId::equals).orElse(false);
    }
}
