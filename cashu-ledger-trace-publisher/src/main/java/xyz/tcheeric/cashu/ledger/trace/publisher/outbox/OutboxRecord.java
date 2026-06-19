package xyz.tcheeric.cashu.ledger.trace.publisher.outbox;

/**
 * A durable outbox row: a signed trace event awaiting at-least-once delivery to
 * the ledger relay set. The {@code operationId} is the idempotency key; the
 * {@code eventJson} is the fully-signed kind-9079 event ready to publish.
 *
 * @param operationId         producer operation id (dashed UUIDv7), primary key
 * @param eventId             the deterministic Nostr event id
 * @param eventJson           the signed event JSON to publish
 * @param createdAtEpochMs    when the row was enqueued
 * @param attempts            delivery attempts so far
 * @param nextAttemptAtEpochMs earliest time the row may be (re)attempted
 * @param status              delivery status
 */
public record OutboxRecord(
        String operationId,
        String eventId,
        String eventJson,
        long createdAtEpochMs,
        int attempts,
        long nextAttemptAtEpochMs,
        OutboxStatus status
) {

    public OutboxRecord {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must be present");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must be present");
        }
        if (eventJson == null || eventJson.isBlank()) {
            throw new IllegalArgumentException("eventJson must be present");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must be present");
        }
    }

    /** A fresh PENDING row available for immediate delivery. */
    public static OutboxRecord pending(String operationId, String eventId, String eventJson,
                                       long nowEpochMs) {
        return new OutboxRecord(operationId, eventId, eventJson, nowEpochMs, 0, nowEpochMs,
                OutboxStatus.PENDING);
    }

    /** A copy recording a failed attempt with the next retry time. */
    public OutboxRecord withFailedAttempt(long nextAttemptAtEpochMs) {
        return new OutboxRecord(operationId, eventId, eventJson, createdAtEpochMs,
                attempts + 1, nextAttemptAtEpochMs, OutboxStatus.PENDING);
    }

    /** A copy marked delivered. */
    public OutboxRecord delivered() {
        return new OutboxRecord(operationId, eventId, eventJson, createdAtEpochMs,
                attempts, nextAttemptAtEpochMs, OutboxStatus.DELIVERED);
    }
}
