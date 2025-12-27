package xyz.tcheeric.cashu.ledger.core.state;

import java.util.List;

/**
 * Verification results for a voucher.
 */
public record VerificationReport(
        String voucherId,
        boolean found,
        boolean signatureValid,
        boolean valueConserved,
        boolean hierarchyComplete,
        boolean stateTransitionsValid,
        String message,
        List<String> auditIssues
) {
    public static VerificationReport notFound(String voucherId) {
        return new VerificationReport(voucherId, false, false, false, false, false, "Voucher not found on relays", List.of());
    }
}
