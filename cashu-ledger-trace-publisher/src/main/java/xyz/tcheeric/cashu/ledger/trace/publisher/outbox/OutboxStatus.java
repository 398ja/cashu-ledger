package xyz.tcheeric.cashu.ledger.trace.publisher.outbox;

/** Delivery status of an {@link OutboxRecord}. */
public enum OutboxStatus {
    /** Awaiting delivery (including rows scheduled for retry). */
    PENDING,
    /** Confirmed delivered to at least one ledger relay. */
    DELIVERED
}
