package xyz.tcheeric.cashu.ledger.trace.core;

import java.util.Optional;

/**
 * A reference to a single Cashu proof within a transaction event. The proof
 * identity for graph joins is the tuple {@code (mintUrl, keysetId, y)} carried by
 * the enclosing {@link TransactionEvent} together with this record's
 * {@code keysetId} and {@code y} — never {@code y} alone (design FR-4).
 *
 * <p>The privacy-mode-dependent fields ({@code secret}, {@code c}, {@code witness},
 * {@code dleq}) are populated according to {@link PrivacyMode}; see
 * {@link ProofRefRedactor}. {@code amount}, {@code keysetId}, and {@code y} are
 * always present.</p>
 *
 * <p>JSON wire names follow NUT-00: {@code amount}, {@code id} (keysetId),
 * {@code secret}, {@code C} (capital), {@code witness}, {@code dleq}, plus the
 * derived {@code y} (design §5.10).</p>
 *
 * @param amount   proof amount in the event's unit; must be {@code >= 0}
 * @param keysetId NUT-00 keyset identifier (lowercase hex)
 * @param y        {@code hash_to_curve(secret)}, 66-char lowercase hex
 * @param secret   raw secret (FULL), HMAC hex (HASHED), or empty (MINIMAL)
 * @param c        unblinded signature {@code C} (FULL), HMAC hex (HASHED), empty (MINIMAL)
 * @param witness  NUT-11/14 witness (FULL), HMAC hex (HASHED), empty (MINIMAL)
 * @param dleq     NUT-12 proof, present only in FULL
 */
public record ProofRef(
        long amount,
        String keysetId,
        String y,
        Optional<String> secret,
        Optional<String> c,
        Optional<String> witness,
        Optional<DleqProof> dleq
) {

    public ProofRef {
        if (amount < 0) {
            throw new IllegalArgumentException("proof amount must not be negative: " + amount);
        }
        if (keysetId == null || keysetId.isBlank()) {
            throw new IllegalArgumentException("proof keysetId must be present");
        }
        if (y == null || y.isBlank()) {
            throw new IllegalArgumentException("proof y must be present");
        }
        secret = secret == null ? Optional.empty() : secret;
        c = c == null ? Optional.empty() : c;
        witness = witness == null ? Optional.empty() : witness;
        dleq = dleq == null ? Optional.empty() : dleq;
    }
}
