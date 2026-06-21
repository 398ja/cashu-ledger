package xyz.tcheeric.cashu.ledger.trace.publisher.outbox;

/** Unchecked wrapper for outbox persistence failures. */
public class OutboxStorageException extends RuntimeException {
    public OutboxStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
