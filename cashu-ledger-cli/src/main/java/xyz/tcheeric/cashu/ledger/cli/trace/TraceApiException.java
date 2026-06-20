package xyz.tcheeric.cashu.ledger.cli.trace;

/**
 * Raised when a trace API request fails (transport error or a non-success HTTP status).
 * Carries a user-facing message describing the failed call and the status when known.
 */
public class TraceApiException extends RuntimeException {

    public TraceApiException(String message) {
        super(message);
    }

    public TraceApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
