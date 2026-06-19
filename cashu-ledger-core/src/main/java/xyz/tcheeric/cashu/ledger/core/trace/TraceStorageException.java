package xyz.tcheeric.cashu.ledger.core.trace;

/** Unchecked wrapper for trace sidecar/storage failures. */
public class TraceStorageException extends RuntimeException {
    public TraceStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
