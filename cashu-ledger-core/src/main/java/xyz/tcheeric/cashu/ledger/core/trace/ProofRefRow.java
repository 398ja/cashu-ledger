package xyz.tcheeric.cashu.ledger.core.trace;

/**
 * One occurrence of a proof tuple in an event: the event id, the role
 * ({@code input} or {@code output}), and the event's transition timestamp.
 */
public record ProofRefRow(String eventId, String role, long transitionAtMs) {
}
