package xyz.tcheeric.cashu.ledger.trace.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates a {@link TransactionEvent} against the per-operation invariants
 * (design §5.9) and the hard size/cardinality limits (NFR-4a). Producers MUST
 * satisfy these before publishing; the ledger MUST re-check them at ingest and
 * reject violators with {@code INVALID_OPERATION} plus the specific sub-code.
 *
 * <p>{@link #validate(TransactionEvent)} returns all violations found (empty when
 * the event is valid) so callers can log comprehensively; the ledger rejects on
 * the first. Cross-event invariants (e.g. MELT_REFUND/MELT pairing, B3) are not
 * checked here — they require the paired event and are enforced by the ledger.</p>
 */
public final class OperationInvariants {

    /** A single invariant failure: a stable sub-code and a human-readable reason. */
    public record Violation(String code, String message) {
    }

    // Hard limits (NFR-4a), decoded sizes.
    public static final int MAX_EVENT_BYTES = 64 * 1024;
    public static final int MAX_INPUTS = 64;
    public static final int MAX_OUTPUTS = 64;
    public static final int MAX_SECRET_BYTES = 8 * 1024;
    public static final int MAX_WITNESS_BYTES = 16 * 1024;
    public static final int MAX_BOLT11_BYTES = 4 * 1024;
    public static final int MAX_ERROR_MESSAGE_BYTES = 1024;

    private static final Pattern LOWER_HEX = Pattern.compile("[0-9a-f]+");
    private static final int Y_HEX_LENGTH = 66;

    private OperationInvariants() {
    }

    /** Returns all invariant violations for {@code event} (empty when valid). */
    public static List<Violation> validate(TransactionEvent event) {
        List<Violation> v = new ArrayList<>();
        if (event == null) {
            v.add(new Violation("NULL_EVENT", "event must not be null"));
            return v;
        }
        checkCardinalityAndBalance(event, v);
        checkRequiredReferences(event, v);
        checkOutputRoles(event, v);
        checkHexEncoding(event, v);
        checkSizeLimits(event, v);
        return v;
    }

    /** Convenience predicate. */
    public static boolean isValid(TransactionEvent event) {
        return validate(event).isEmpty();
    }

    private static void checkCardinalityAndBalance(TransactionEvent e, List<Violation> v) {
        int in = e.inputs().size();
        int out = e.outputs().size();
        long sumIn = sum(e.inputs());
        long sumOut = sum(e.outputs());
        long fee = e.feeAmount().orElse(0L);
        if (fee < 0) {
            v.add(new Violation("A0", "fee must not be negative: " + fee));
        }
        switch (e.kind()) {
            case MINT_QUOTE_REQUESTED, MELT_QUOTE_REQUESTED, MINT_FAILED -> {
                if (in != 0) {
                    v.add(new Violation("E1", e.kind() + " must have no inputs"));
                }
                if (out != 0) {
                    v.add(new Violation("E2", e.kind() + " must have no outputs"));
                }
            }
            case MINT -> {
                if (in != 0) {
                    v.add(new Violation("E1", "MINT must have no inputs"));
                }
                if (out < 1) {
                    v.add(new Violation("O1", "MINT must have at least one output"));
                }
                e.lightning().flatMap(LightningRef::amount).ifPresent(quoteAmount -> {
                    if (sumOut != quoteAmount) {
                        v.add(new Violation("B0",
                                "MINT outputs (" + sumOut + ") must equal quote amount (" + quoteAmount + ")"));
                    }
                });
            }
            case SWAP -> {
                if (in < 1) {
                    v.add(new Violation("I1", "SWAP must have at least one input"));
                }
                if (out < 1) {
                    v.add(new Violation("O1", "SWAP must have at least one output"));
                }
                if (sumIn != sumOut + fee) {
                    v.add(new Violation("B1",
                            "SWAP balance: inputs " + sumIn + " != outputs " + sumOut + " + fee " + fee));
                }
            }
            case MELT -> {
                if (in < 1) {
                    v.add(new Violation("I1", "MELT must have at least one input"));
                }
                if (e.feeAmount().isEmpty()) {
                    v.add(new Violation("F1", "MELT requires a fee amount"));
                }
                e.lightning().flatMap(LightningRef::amount).ifPresent(quoteAmount -> {
                    if (sumIn != quoteAmount + fee + sumOut) {
                        v.add(new Violation("B2",
                                "MELT balance: inputs " + sumIn + " != quote " + quoteAmount
                                        + " + fee " + fee + " + outputs " + sumOut));
                    }
                });
            }
            case MELT_REFUND -> {
                if (in != 0) {
                    v.add(new Violation("E1", "MELT_REFUND must have no inputs"));
                }
                if (out < 1) {
                    v.add(new Violation("O1", "MELT_REFUND must have at least one output"));
                }
                if (e.lightning().map(LightningRef::partial).orElse(false) == false) {
                    v.add(new Violation("B3_PARTIAL_SETTLEMENT",
                            "MELT_REFUND requires lightning.partial=true"));
                }
            }
            case RESTORE -> {
                if (in != 0) {
                    v.add(new Violation("E1", "RESTORE must have no inputs"));
                }
                if (out < 1) {
                    v.add(new Violation("O1", "RESTORE must have at least one output"));
                }
            }
            case SEND, RECEIVE -> {
                if (in < 1) {
                    v.add(new Violation("I1", e.kind() + " must have at least one input"));
                }
                if (out != 0) {
                    v.add(new Violation("E2", e.kind() + " must have no outputs"));
                }
            }
            case MELT_FAILED -> {
                if (in < 1) {
                    v.add(new Violation("I1", "MELT_FAILED must have at least one input"));
                }
                if (out != 0) {
                    v.add(new Violation("E2", "MELT_FAILED must have no outputs"));
                }
            }
            case EVENT_PRUNED ->
                    v.add(new Violation("P1", "EVENT_PRUNED is ledger-internal and must not be published"));
        }
    }

    private static void checkRequiredReferences(TransactionEvent e, List<Violation> v) {
        switch (e.kind()) {
            case MINT_QUOTE_REQUESTED, MELT_QUOTE_REQUESTED, MINT, MELT, MINT_FAILED, MELT_FAILED, MELT_REFUND -> {
                if (e.lightning().isEmpty()) {
                    v.add(new Violation("QUOTE_REQUIRED", e.kind() + " requires a lightning quote reference"));
                }
            }
            case SEND -> {
                if (e.bundleId().isEmpty()) {
                    v.add(new Violation("L1", "SEND requires a bundle_id"));
                }
            }
            case RECEIVE -> {
                if (e.bundleId().isEmpty()) {
                    v.add(new Violation("L2", "RECEIVE requires a bundle_id"));
                }
            }
            default -> {
            }
        }
        if ((e.kind() == OperationKind.MINT_FAILED || e.kind() == OperationKind.MELT_FAILED)
                && e.errorCode().isEmpty()) {
            v.add(new Violation("X1", e.kind() + " requires an error_code"));
        }
    }

    private static void checkOutputRoles(TransactionEvent e, List<Violation> v) {
        // Alignment is enforced by the record; here we only confirm the count rule
        // holds for non-empty role lists (defensive; R3).
        if (!e.outputRoles().isEmpty() && e.outputRoles().size() != e.outputs().size()) {
            v.add(new Violation("R3", "output_role count must equal output count"));
        }
    }

    private static void checkHexEncoding(TransactionEvent e, List<Violation> v) {
        for (ProofRef p : e.inputs()) {
            checkProofHex(p, "input", v);
        }
        for (ProofRef p : e.outputs()) {
            checkProofHex(p, "output", v);
        }
    }

    private static void checkProofHex(ProofRef p, String role, List<Violation> v) {
        if (!LOWER_HEX.matcher(p.keysetId()).matches()) {
            v.add(new Violation("H1", role + " keysetId must be lowercase hex: " + p.keysetId()));
        }
        if (p.y().length() != Y_HEX_LENGTH || !LOWER_HEX.matcher(p.y()).matches()) {
            v.add(new Violation("H1", role + " y must be 66-char lowercase hex"));
        }
    }

    private static void checkSizeLimits(TransactionEvent e, List<Violation> v) {
        if (e.inputs().size() > MAX_INPUTS) {
            v.add(new Violation("TOO_MANY_INPUTS", "inputs exceed " + MAX_INPUTS));
        }
        if (e.outputs().size() > MAX_OUTPUTS) {
            v.add(new Violation("TOO_MANY_OUTPUTS", "outputs exceed " + MAX_OUTPUTS));
        }
        for (ProofRef p : e.inputs()) {
            checkProofFieldSizes(p, v);
        }
        for (ProofRef p : e.outputs()) {
            checkProofFieldSizes(p, v);
        }
        e.lightning().flatMap(LightningRef::bolt11).ifPresent(b -> {
            if (utf8Length(b) > MAX_BOLT11_BYTES) {
                v.add(new Violation("BOLT11_TOO_LARGE", "bolt11 exceeds " + MAX_BOLT11_BYTES + " bytes"));
            }
        });
        e.errorMessage().ifPresent(m -> {
            if (utf8Length(m) > MAX_ERROR_MESSAGE_BYTES) {
                v.add(new Violation("ERROR_MESSAGE_TOO_LARGE",
                        "error_message exceeds " + MAX_ERROR_MESSAGE_BYTES + " bytes"));
            }
        });
        if (utf8Length(CanonicalJson.content(e)) > MAX_EVENT_BYTES) {
            v.add(new Violation("EVENT_TOO_LARGE", "event content exceeds " + MAX_EVENT_BYTES + " bytes"));
        }
    }

    private static void checkProofFieldSizes(ProofRef p, List<Violation> v) {
        p.secret().ifPresent(s -> {
            if (utf8Length(s) > MAX_SECRET_BYTES) {
                v.add(new Violation("SECRET_TOO_LARGE", "secret exceeds " + MAX_SECRET_BYTES + " bytes"));
            }
        });
        p.witness().ifPresent(w -> {
            if (utf8Length(w) > MAX_WITNESS_BYTES) {
                v.add(new Violation("WITNESS_TOO_LARGE", "witness exceeds " + MAX_WITNESS_BYTES + " bytes"));
            }
        });
    }

    private static long sum(List<ProofRef> proofs) {
        long total = 0;
        for (ProofRef p : proofs) {
            total += p.amount();
        }
        return total;
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
