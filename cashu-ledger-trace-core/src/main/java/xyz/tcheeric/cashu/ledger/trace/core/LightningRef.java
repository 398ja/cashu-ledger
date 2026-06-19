package xyz.tcheeric.cashu.ledger.trace.core;

import java.time.Instant;
import java.util.Optional;

/**
 * A reference linking a transaction event to a Lightning mint/melt quote.
 *
 * @param quoteId        producer-supplied dashed UUIDv7 quote id
 * @param mintUrl        mint that owns the quote (duplicated for cross-mint disambiguation)
 * @param bolt11         invoice (FULL raw / HASHED HMAC / MINIMAL empty)
 * @param paymentHash    payment hash (FULL and HASHED; MINIMAL empty)
 * @param amount         quote amount normalised to the event's unit (UN1)
 * @param expiresAt      when the quote expires
 * @param quoteOperation which side of the quote this is
 *                       ({@link OperationKind#MINT_QUOTE_REQUESTED} or
 *                       {@link OperationKind#MELT_QUOTE_REQUESTED})
 * @param partial        {@code true} on MELT/MELT_REFUND when Lightning settled only partially
 */
public record LightningRef(
        String quoteId,
        String mintUrl,
        Optional<String> bolt11,
        Optional<String> paymentHash,
        Optional<Long> amount,
        Optional<Instant> expiresAt,
        OperationKind quoteOperation,
        boolean partial
) {

    public LightningRef {
        if (quoteId == null || quoteId.isBlank()) {
            throw new IllegalArgumentException("lightning quoteId must be present");
        }
        if (quoteOperation != OperationKind.MINT_QUOTE_REQUESTED
                && quoteOperation != OperationKind.MELT_QUOTE_REQUESTED) {
            throw new IllegalArgumentException(
                    "quoteOperation must be a quote-request kind, was: " + quoteOperation);
        }
        bolt11 = bolt11 == null ? Optional.empty() : bolt11;
        paymentHash = paymentHash == null ? Optional.empty() : paymentHash;
        amount = amount == null ? Optional.empty() : amount;
        expiresAt = expiresAt == null ? Optional.empty() : expiresAt;
    }
}
