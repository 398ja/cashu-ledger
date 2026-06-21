package xyz.tcheeric.cashu.ledger.trace.core;

/**
 * The derived live/terminal classification of a transaction event, used for
 * activity filtering on listing endpoints and for terminal-sub-DAG pruning.
 *
 * <p>An event is {@link #TERMINAL} when it is a terminal-kind operation, its
 * referenced voucher is in a terminal status, or all of its output proofs have
 * been consumed downstream; otherwise it is {@link #ACTIVE}. The classification
 * is computed by the ledger from already-stored data (design §5.4.1).</p>
 */
public enum EventActivity {
    ACTIVE,
    TERMINAL
}
