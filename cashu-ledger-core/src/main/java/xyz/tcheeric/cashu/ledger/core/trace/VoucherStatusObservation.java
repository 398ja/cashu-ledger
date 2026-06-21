package xyz.tcheeric.cashu.ledger.core.trace;

import java.time.Instant;
import java.util.Optional;

/**
 * A single observation of a voucher's published state (kind-30078), reduced to what
 * the trace subsystem needs: the voucher id, its status, the monotonic state version
 * that orders revisions, when the transition occurred, whether the status is terminal,
 * and the issuer attribution used for back-fill (design §5.4.1).
 *
 * @param voucherId     the voucher identifier (the {@code d} tag, prefix stripped)
 * @param status        the wire status value (e.g. {@code redeemed}, {@code expired})
 * @param stateVersion  the monotonic revision counter; higher supersedes
 * @param transitionAt  when the transition occurred
 * @param terminal      whether {@code status} is a terminal state
 * @param issuerId      the issuer id, when present
 * @param issuerPubkey  the issuer pubkey, when present
 */
public record VoucherStatusObservation(
        String voucherId,
        String status,
        long stateVersion,
        Instant transitionAt,
        boolean terminal,
        Optional<String> issuerId,
        Optional<String> issuerPubkey) {
}
