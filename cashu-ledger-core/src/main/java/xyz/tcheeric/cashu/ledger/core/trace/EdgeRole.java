package xyz.tcheeric.cashu.ledger.core.trace;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * The kind of directed relationship between two transaction events in the graph
 * (design §5.5 walk response):
 *
 * <ul>
 *   <li>{@link #SPEND} — a proof produced upstream was consumed downstream.</li>
 *   <li>{@link #QUOTE} — a quote-only event preceded its settlement.</li>
 *   <li>{@link #POSSESSION} — a SEND↔RECEIVE bundle moved custody (no spend).</li>
 *   <li>{@link #TRANSFER} — two mint-state-change events on different mints share a transfer id.</li>
 *   <li>{@link #ATTEMPT} — a failed operation referenced an input it did not consume.</li>
 *   <li>{@link #TOMBSTONE} — the downstream node is a pruned-event placeholder.</li>
 * </ul>
 */
public enum EdgeRole {
    SPEND,
    QUOTE,
    POSSESSION,
    TRANSFER,
    ATTEMPT,
    TOMBSTONE;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
