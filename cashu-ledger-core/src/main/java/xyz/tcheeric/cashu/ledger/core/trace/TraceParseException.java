package xyz.tcheeric.cashu.ledger.core.trace;

/** Thrown when a raw kind-9079 event JSON cannot be parsed into a transaction event. */
public class TraceParseException extends RuntimeException {
    public TraceParseException(String message) {
        super(message);
    }

    public TraceParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
