package xyz.tcheeric.cashu.ledger.web.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A Lightning quote reference in an API response. {@code bolt11} / {@code paymentHash}
 * are included only at full/hashed access; {@code amount} and {@code partial} are
 * always safe to expose.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LightningView(
        String quoteId,
        String mintUrl,
        String quoteOperation,
        Long amount,
        Boolean partial,
        String bolt11,
        String paymentHash
) {
}
