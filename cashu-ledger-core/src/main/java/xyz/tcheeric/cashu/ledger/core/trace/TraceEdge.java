package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.List;
import java.util.Optional;

/**
 * A derived directed edge between two events (design §5.5). Edges are computed at
 * read time from the stored events; no edge records are persisted.
 *
 * @param fromEventId          the upstream event id
 * @param toEventId            the downstream event id
 * @param role                 the relationship kind
 * @param keysetId             proof keyset (spend/attempt edges)
 * @param y                    proof identifier (spend/attempt edges)
 * @param amount               proof amount (spend edges)
 * @param quoteId              quote id (quote edges)
 * @param bundleId             bundle id (possession edges)
 * @param transferId           transfer id (transfer edges)
 * @param doubleConsume        true if this proof is consumed by more than one event
 * @param conflictingConsumers the conflicting consumer event ids when doubleConsume
 */
public record TraceEdge(
        String fromEventId,
        String toEventId,
        EdgeRole role,
        Optional<String> keysetId,
        Optional<String> y,
        Optional<Long> amount,
        Optional<String> quoteId,
        Optional<String> bundleId,
        Optional<String> transferId,
        boolean doubleConsume,
        List<String> conflictingConsumers
) {

    public TraceEdge {
        keysetId = keysetId == null ? Optional.empty() : keysetId;
        y = y == null ? Optional.empty() : y;
        amount = amount == null ? Optional.empty() : amount;
        quoteId = quoteId == null ? Optional.empty() : quoteId;
        bundleId = bundleId == null ? Optional.empty() : bundleId;
        transferId = transferId == null ? Optional.empty() : transferId;
        conflictingConsumers = conflictingConsumers == null ? List.of() : List.copyOf(conflictingConsumers);
    }

    /** A spend edge for a proof tuple. */
    public static TraceEdge spend(String from, String to, String keysetId, String y, long amount,
                                  boolean doubleConsume, List<String> conflicting) {
        return new TraceEdge(from, to, EdgeRole.SPEND, Optional.of(keysetId), Optional.of(y),
                Optional.of(amount), Optional.empty(), Optional.empty(), Optional.empty(),
                doubleConsume, conflicting);
    }

    /** An attempt edge (failed op referenced but did not consume a proof). */
    public static TraceEdge attempt(String from, String to, String keysetId, String y) {
        return new TraceEdge(from, to, EdgeRole.ATTEMPT, Optional.of(keysetId), Optional.of(y),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, List.of());
    }

    public static TraceEdge quote(String from, String to, String quoteId) {
        return new TraceEdge(from, to, EdgeRole.QUOTE, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(quoteId), Optional.empty(), Optional.empty(), false, List.of());
    }

    public static TraceEdge possession(String from, String to, String bundleId) {
        return new TraceEdge(from, to, EdgeRole.POSSESSION, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(bundleId), Optional.empty(), false, List.of());
    }

    public static TraceEdge transfer(String from, String to, String transferId) {
        return new TraceEdge(from, to, EdgeRole.TRANSFER, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(transferId), false, List.of());
    }

    /** A stable de-duplication key for an edge. */
    public String dedupeKey() {
        return fromEventId + "|" + toEventId + "|" + role + "|" + y.orElse("")
                + "|" + quoteId.orElse("") + "|" + bundleId.orElse("") + "|" + transferId.orElse("");
    }
}
