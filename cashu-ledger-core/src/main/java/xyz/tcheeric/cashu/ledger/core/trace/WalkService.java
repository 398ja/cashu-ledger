package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Walks the transaction graph from an anchor using deterministic breadth-first
 * traversal with a {@code (transition_at, event_id)} tie-break, bounded by depth
 * and a maximum node count (design §5.5 / FR-12/14). Edges are derived lazily by
 * {@link EdgeDeriver}.
 */
public final class WalkService {

    /** Walk direction. */
    public enum Direction { UP, DOWN, BOTH }

    private final TraceEventStore store;
    private final EdgeDeriver edgeDeriver;
    private final Tombstones tombstones;

    public WalkService(TraceEventStore store, EdgeDeriver edgeDeriver) {
        this(store, edgeDeriver, Tombstones.NONE);
    }

    public WalkService(TraceEventStore store, EdgeDeriver edgeDeriver, Tombstones tombstones) {
        this.store = store;
        this.edgeDeriver = edgeDeriver;
        this.tombstones = tombstones;
    }

    /** Walks from a single anchor event. */
    public WalkResult walkFromEvent(String eventId, Direction direction, int depth, int maxNodes) {
        return store.findByEventId(eventId)
                .map(anchor -> walk(List.of(anchor), direction, depth, maxNodes))
                .orElseGet(() -> new WalkResult(direction.name().toLowerCase(), depth,
                        List.of(), List.of(), false, Optional.empty(), 0));
    }

    /** Walks from the events that reference a proof tuple (its producer and consumers). */
    public WalkResult walkFromProof(String mintUrl, String keysetId, String y,
                                    Direction direction, int depth, int maxNodes) {
        List<StoredEvent> anchors = new ArrayList<>();
        anchors.addAll(store.findByOutputProofRef(mintUrl, keysetId, y, maxNodes));
        anchors.addAll(store.findByInputProofRef(mintUrl, keysetId, y, maxNodes));
        return walk(anchors, direction, depth, maxNodes);
    }

    private WalkResult walk(List<StoredEvent> anchors, Direction direction, int depth, int maxNodes) {
        Set<String> visited = new LinkedHashSet<>();
        List<WalkResult.WalkNode> nodes = new ArrayList<>();
        Map<String, TraceEdge> edges = new LinkedHashMap<>();
        Deque<Frontier> queue = new ArrayDeque<>();

        seedAnchors(anchors).forEach(id -> {
            if (visited.add(id)) {
                queue.add(new Frontier(id, 0));
            }
        });

        boolean truncated = false;
        int prunedCount = 0;
        Optional<String> cursor = Optional.empty();
        while (!queue.isEmpty()) {
            if (nodes.size() >= maxNodes) {
                truncated = true;
                cursor = Optional.of(queue.peek().eventId());
                break;
            }
            Frontier current = queue.poll();
            Optional<StoredEvent> stored = store.findByEventId(current.eventId());
            if (stored.isEmpty()) {
                continue; // raw payload gone and not tombstoned: not reachable
            }
            nodes.add(toNode(stored.get().event()));
            if (tombstones.isTombstoned(current.eventId())) {
                prunedCount++; // pruned hop: render as placeholder, do not expand past it
                continue;
            }
            if (current.depth() >= depth) {
                continue;
            }
            for (String neighbour : expand(stored.get(), direction, edges)) {
                if (visited.add(neighbour)) {
                    queue.add(new Frontier(neighbour, current.depth() + 1));
                }
            }
        }
        return new WalkResult(direction.name().toLowerCase(), depth, nodes,
                new ArrayList<>(edges.values()), truncated, cursor, prunedCount);
    }

    /** Adds incident edges to the accumulator and returns the deterministically-sorted neighbour ids. */
    private List<String> expand(StoredEvent event, Direction direction, Map<String, TraceEdge> edges) {
        String eventId = event.event().eventId().orElseThrow();
        List<TraceEdge> incident = new ArrayList<>();
        if (direction == Direction.DOWN || direction == Direction.BOTH) {
            incident.addAll(edgeDeriver.outgoing(event));
        }
        if (direction == Direction.UP || direction == Direction.BOTH) {
            incident.addAll(edgeDeriver.incoming(event));
        }
        Set<String> neighbours = new LinkedHashSet<>();
        for (TraceEdge edge : incident) {
            edges.putIfAbsent(edge.dedupeKey(), edge);
            String neighbour = edge.fromEventId().equals(eventId) ? edge.toEventId() : edge.fromEventId();
            if (!neighbour.equals(eventId)) {
                neighbours.add(neighbour);
            }
        }
        if (neighbours.size() <= 1) {
            return List.copyOf(neighbours); // nothing to order; skip the per-neighbour time lookup
        }
        return neighbours.stream()
                .sorted(Comparator
                        .comparingLong(this::transitionAtOf)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private long transitionAtOf(String eventId) {
        return store.findByEventId(eventId)
                .map(s -> s.event().transitionAt().toEpochMilli())
                .orElse(Long.MAX_VALUE);
    }

    private static List<String> seedAnchors(List<StoredEvent> anchors) {
        return anchors.stream()
                .map(a -> a.event().eventId().orElseThrow())
                .distinct()
                .sorted()
                .toList();
    }

    private static WalkResult.WalkNode toNode(TransactionEvent e) {
        return new WalkResult.WalkNode(e.eventId().orElseThrow(), e.kind().wireValue(),
                e.mintUrl(), e.transitionAt().toEpochMilli());
    }

    private record Frontier(String eventId, int depth) {
    }
}
