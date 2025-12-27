package xyz.tcheeric.cashu.ledger.core.state;

import java.util.List;

/**
 * History fetch result including status changes and any audit warnings.
 */
public record HistoryResult(
        List<StatusChange> events,
        List<String> warnings
) {
}
