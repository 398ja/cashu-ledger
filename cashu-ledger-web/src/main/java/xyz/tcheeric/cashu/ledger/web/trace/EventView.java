package xyz.tcheeric.cashu.ledger.web.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A transaction event in an API response, shaped to the caller's access level. The
 * {@code returnedPrivacyMode} tells the consumer which shape they actually received
 * (which may be more restrictive than how the event was stored).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventView(
        String eventId,
        String operationId,
        String kind,
        String mintUrl,
        String unit,
        String transitionAt,
        String producerPubkey,
        String initiatorPubkey,
        List<ProofView> inputs,
        List<ProofView> outputs,
        LightningView lightning,
        String voucherRef,
        String issuerId,
        String issuerPubkey,
        String issuerProvenance,
        String activity,
        String activityReason,
        Long feeAmount,
        String returnedPrivacyMode,
        int schemaVersion
) {
}
