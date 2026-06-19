package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex.IssuerBackfillResult;

/**
 * Back-fills issuer attribution for events that were published before their voucher was
 * bound (design §5.3.1, option (c)). When a kind-30078 voucher event arrives carrying
 * proofs, the watcher derives {@code (mint_url, keyset_id, Y) -> issuer} mappings and
 * hands them here; this component records each mapping in the {@code proof_issuer_backfill}
 * sidecar and applies it to indexed events whose issuer is unset. The raw signed Nostr
 * event is never modified, so signatures stay valid; read responses surface the divergence
 * via {@code issuerProvenance: "index_backfill"}.
 */
public final class IssuerBackfill {

    private static final Logger LOGGER = LoggerFactory.getLogger(IssuerBackfill.class);

    private final SqliteSidecarIndex index;

    public IssuerBackfill(SqliteSidecarIndex index) {
        this.index = index;
    }

    /**
     * Back-fills the given issuer for every supplied proof identity, returning the number
     * of event rows updated. Overwrites of a previously back-filled issuer are logged at WARN.
     */
    public int backfill(String voucherId, String issuerId, String issuerPubkey,
                        Collection<ProofIdentity> proofs) {
        int rowsUpdated = 0;
        for (ProofIdentity proof : proofs) {
            IssuerBackfillResult result = index.applyIssuerBackfill(
                    proof.mintUrl(), proof.keysetId(), proof.y(), issuerId, issuerPubkey);
            rowsUpdated += result.rowsUpdated();
            if (result.outcome() == IssuerBackfillResult.Outcome.OVERWRITTEN) {
                LOGGER.warn("issuer_backfill_overwrite voucher_id={} y={} previous={} new={}",
                        voucherId, proof.y(), result.previousIssuerId().orElse(null), issuerId);
            }
        }
        LOGGER.info("issuer_backfill_completed voucher_id={} proof_count={} rows_updated={}",
                voucherId, proofs.size(), rowsUpdated);
        return rowsUpdated;
    }
}
