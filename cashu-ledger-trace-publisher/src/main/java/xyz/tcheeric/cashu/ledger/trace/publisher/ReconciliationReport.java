package xyz.tcheeric.cashu.ledger.trace.publisher;

import java.util.List;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxRecord;

/**
 * The outcome of a reconciliation pass (design FR-036).
 *
 * @param domainOperations the number of domain operations that should have been traced
 * @param outboxPending    rows still awaiting delivery (in flight)
 * @param ledgerIndexed    events the ledger has ingested (from its {@code /stats})
 * @param missing          operations not yet visible in the ledger ({@code domain - ledger})
 * @param unaccounted      missing operations the outbox cannot explain ({@code missing - pending})
 * @param stuckEvents      pending rows that have exceeded the retry threshold
 * @param alert            whether operator attention is warranted (unaccounted or stuck)
 */
public record ReconciliationReport(
        long domainOperations,
        long outboxPending,
        long ledgerIndexed,
        long missing,
        long unaccounted,
        List<OutboxRecord> stuckEvents,
        boolean alert) {
}
