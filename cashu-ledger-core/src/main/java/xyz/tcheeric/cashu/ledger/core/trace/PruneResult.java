package xyz.tcheeric.cashu.ledger.core.trace;

/**
 * The outcome of one retention pass (design §5.11).
 *
 * @param agePruned     events pruned by the age threshold
 * @param terminalPruned events pruned by terminal sub-DAG pruning
 * @param subDagPaused  whether sub-DAG pruning was skipped because the activity cache was stale
 */
public record PruneResult(int agePruned, int terminalPruned, boolean subDagPaused) {

    public int total() {
        return agePruned + terminalPruned;
    }
}
