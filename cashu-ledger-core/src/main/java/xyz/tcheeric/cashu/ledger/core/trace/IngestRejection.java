package xyz.tcheeric.cashu.ledger.core.trace;

/**
 * A reason an event was rejected at ingest: a stable code and a human-readable
 * message (design §5.9 / §4.5 error codes).
 */
public record IngestRejection(String code, String message) {
}
