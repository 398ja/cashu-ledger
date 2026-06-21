package xyz.tcheeric.cashu.ledger.core.trace;

/**
 * The canonical primary key for a proof reference across the trace subsystem: the full
 * {@code (mint_url, keyset_id, Y)} tuple (FR-4). The {@code mint_url} component is never
 * dropped — two mints whose 8-byte keyset ids collide must not share index or back-fill
 * rows (design §5.4 isolation rationale).
 */
public record ProofIdentity(String mintUrl, String keysetId, String y) {
}
