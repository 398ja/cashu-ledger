package xyz.tcheeric.cashu.ledger.trace.publisher;

/**
 * Thrown when a traceability event cannot be enqueued for delivery — most notably
 * under the {@link OverflowPolicy#BLOCK_AND_ALERT} policy when the outbox stays at
 * capacity past the bounded block window. The caller decides whether to fail the
 * user-facing operation or proceed without traceability.
 */
public class TraceabilityPublishException extends RuntimeException {

    private final String code;

    public TraceabilityPublishException(String code, String message) {
        super(message);
        this.code = code;
    }

    public TraceabilityPublishException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** A stable, machine-readable error code (e.g. {@code OUTBOX_FULL}). */
    public String getCode() {
        return code;
    }
}
