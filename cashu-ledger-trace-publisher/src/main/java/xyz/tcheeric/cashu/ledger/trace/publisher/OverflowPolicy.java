package xyz.tcheeric.cashu.ledger.trace.publisher;

import java.util.Locale;

/**
 * Policy applied when the durable outbox is at capacity (design §4.2 FR-11a).
 *
 * <ul>
 *   <li>{@link #BLOCK_AND_ALERT} (default) — block briefly for drain, then throw
 *       {@link TraceabilityPublishException}; data loss is never silent.</li>
 *   <li>{@link #DROP_OLDEST_AND_ALERT} — drop the oldest pending row and alert.</li>
 *   <li>{@link #DROP_NEW_AND_ALERT} — refuse the new publish and alert.</li>
 *   <li>{@link #FAIL_OPEN} — accept without enqueueing (strongly discouraged).</li>
 * </ul>
 *
 * Producers running a lossy policy MUST tag every event with the policy name so
 * consumers can interpret gaps (FR-11b); {@link #isLossy()} identifies those.
 */
public enum OverflowPolicy {

    BLOCK_AND_ALERT,
    DROP_OLDEST_AND_ALERT,
    DROP_NEW_AND_ALERT,
    FAIL_OPEN;

    /** Whether this policy can drop or skip events, requiring an {@code overflow_policy} tag. */
    public boolean isLossy() {
        return this != BLOCK_AND_ALERT;
    }

    /** The lowercase wire form used in the {@code overflow_policy} tag. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
