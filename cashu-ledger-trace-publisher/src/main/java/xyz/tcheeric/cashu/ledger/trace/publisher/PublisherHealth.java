package xyz.tcheeric.cashu.ledger.trace.publisher;

/**
 * A snapshot of publisher health for operators.
 *
 * @param pendingCount    rows currently awaiting delivery in the outbox
 * @param capacity        the configured outbox capacity
 * @param overflowPolicy  the active overflow policy
 */
public record PublisherHealth(long pendingCount, long capacity, OverflowPolicy overflowPolicy) {

    /** Whether the outbox is at or beyond capacity. */
    public boolean isOverCapacity() {
        return pendingCount >= capacity;
    }
}
