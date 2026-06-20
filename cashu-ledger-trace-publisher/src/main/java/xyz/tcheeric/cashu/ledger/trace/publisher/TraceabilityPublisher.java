package xyz.tcheeric.cashu.ledger.trace.publisher;

import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * The producer-facing API. {@link #publish(TransactionEvent)} is non-blocking in
 * steady state: it redacts, signs, and enqueues the event, returning immediately
 * (design US-5 / FR-15). Delivery happens asynchronously via the outbox dispatcher.
 */
public interface TraceabilityPublisher {

    /**
     * Redacts (per the event's privacy mode), signs, and enqueues a transaction
     * event for asynchronous delivery.
     *
     * @throws TraceabilityPublishException if the event is invalid, or under
     *         {@link OverflowPolicy#BLOCK_AND_ALERT} when the outbox stays full
     */
    void publish(TransactionEvent event);

    /** A current health snapshot (outbox depth, capacity, overflow policy). */
    PublisherHealth health();
}
