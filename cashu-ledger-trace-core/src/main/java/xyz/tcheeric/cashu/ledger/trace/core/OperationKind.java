package xyz.tcheeric.cashu.ledger.trace.core;

import java.util.Locale;

/**
 * The classification of a Cashu token-level operation captured as a transaction
 * event. Kinds are partitioned into three categories with distinct DAG semantics
 * (see the design document §4.1 FR-1a):
 *
 * <ul>
 *   <li><b>Mint state-change</b> ({@link #MINT}, {@link #SWAP}, {@link #MELT},
 *       {@link #MELT_REFUND}, {@link #RESTORE}) — the only kinds that produce
 *       proof-derived <i>spend</i> edges in the graph.</li>
 *   <li><b>Possession</b> ({@link #SEND}, {@link #RECEIVE}) — off-mint custody
 *       changes; they never consume proofs and link via a bundle identifier.</li>
 *   <li><b>Quote / failure / internal</b> — reference, attempt, or audit nodes.</li>
 * </ul>
 */
public enum OperationKind {

    MINT_QUOTE_REQUESTED,
    MINT,
    SWAP,
    SEND,
    RECEIVE,
    MELT_QUOTE_REQUESTED,
    MELT,
    MELT_REFUND,
    RESTORE,
    MINT_FAILED,
    MELT_FAILED,
    EVENT_PRUNED;

    /**
     * Whether this kind reflects an operation that consumed or produced proofs at
     * the mint. Only these kinds contribute proof-derived spend edges.
     */
    public boolean isMintStateChange() {
        return switch (this) {
            case MINT, SWAP, MELT, MELT_REFUND, RESTORE -> true;
            default -> false;
        };
    }

    /** Whether this kind describes an off-mint custody change (bundle-linked). */
    public boolean isPossession() {
        return this == SEND || this == RECEIVE;
    }

    /**
     * Whether outputs of this kind can begin a proof-derived edge. {@code MINT},
     * {@code RESTORE}, and {@code MELT_REFUND} are graph roots; {@code SWAP} both
     * produces and consumes.
     */
    public boolean isEdgeProducer() {
        return switch (this) {
            case MINT, SWAP, RESTORE, MELT_REFUND -> true;
            default -> false;
        };
    }

    /** Whether inputs of this kind can terminate a proof-derived edge. */
    public boolean isEdgeConsumer() {
        return this == SWAP || this == MELT;
    }

    /** Whether this kind finalises a chain and is never re-evaluated for activity. */
    public boolean isTerminalKind() {
        return switch (this) {
            case MELT, MELT_FAILED, MINT_FAILED, EVENT_PRUNED -> true;
            default -> false;
        };
    }

    /** Whether this kind is a quote-only reference node carrying no proofs. */
    public boolean isQuoteRequest() {
        return this == MINT_QUOTE_REQUESTED || this == MELT_QUOTE_REQUESTED;
    }

    /** The lowercase wire form used in the {@code op} tag (e.g. {@code "swap"}). */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses the lowercase {@code op} tag value back to a kind.
     *
     * @throws IllegalArgumentException if the value is not a recognised kind
     */
    public static OperationKind fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("operation kind value must not be null");
        }
        return OperationKind.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
