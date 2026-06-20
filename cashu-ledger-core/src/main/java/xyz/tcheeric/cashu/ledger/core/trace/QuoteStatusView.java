package xyz.tcheeric.cashu.ledger.core.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Synthesised lifecycle of a Lightning quote (design §5.5). The status is computed from the
 * presence of a settlement event and the quote's expiry, not stored on any event.
 *
 * @param quoteId        the raw producer-supplied quote id
 * @param mintUrl        the mint the quote belongs to
 * @param operation      {@code mint} or {@code melt}
 * @param status         {@code pending}, {@code succeeded}, {@code failed}, or {@code expired}
 * @param requestedAt    when the quote was requested, ISO-8601
 * @param expiresAt      when the quote expires, ISO-8601, if known
 * @param settledEventId the settling event id, when settled
 * @param failureReason  the failure reason, when failed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteStatusView(
        String quoteId,
        String mintUrl,
        String operation,
        String status,
        String requestedAt,
        String expiresAt,
        String settledEventId,
        String failureReason) {

    public enum Status { PENDING, SUCCEEDED, FAILED, EXPIRED }
}
