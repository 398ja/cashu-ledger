package xyz.tcheeric.cashu.ledger.core.trace;

/**
 * A distinct {@code (mintUrl, keysetId)} pair under which a given {@code y} was
 * observed. Used to group proof history and to detect ambiguous-proof lookups
 * (design §5.5 — {@code 409 AMBIGUOUS_PROOF}).
 */
public record ProofCandidate(String mintUrl, String keysetId) {
}
