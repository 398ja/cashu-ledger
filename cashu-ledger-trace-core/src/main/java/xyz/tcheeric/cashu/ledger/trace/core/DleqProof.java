package xyz.tcheeric.cashu.ledger.trace.core;

import java.util.Optional;

/**
 * A NUT-12 discrete-log equality proof attached to a {@link ProofRef}. Present
 * only in {@link PrivacyMode#FULL}.
 *
 * @param e the challenge scalar, lowercase hex
 * @param s the response scalar, lowercase hex
 * @param r the optional blinding factor, lowercase hex
 */
public record DleqProof(String e, String s, Optional<String> r) {

    public DleqProof {
        if (e == null || s == null) {
            throw new IllegalArgumentException("dleq proof requires both e and s");
        }
        if (r == null) {
            r = Optional.empty();
        }
    }
}
