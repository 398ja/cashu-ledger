package xyz.tcheeric.cashu.ledger.web.controller;

/**
 * Raised when a sidecar-dependent read cannot be served because the index is unavailable,
 * rebuilding, or lagging the system of record (design §5.4 — {@code 503 INDEX_UNAVAILABLE} /
 * {@code INDEX_LAGGED}). Carries the error code and a Retry-After hint.
 */
public class IndexNotServableException extends RuntimeException {

    private final String code;
    private final int retryAfterSeconds;

    public IndexNotServableException(String code, int retryAfterSeconds) {
        super(code);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() {
        return code;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
