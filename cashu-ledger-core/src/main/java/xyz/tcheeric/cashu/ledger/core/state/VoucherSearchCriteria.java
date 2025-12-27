package xyz.tcheeric.cashu.ledger.core.state;

import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;

import java.time.Instant;

/**
 * Criteria for searching vouchers.
 */
public record VoucherSearchCriteria(
        String issuerId,
        VoucherStatus status,
        Instant since,
        Instant until,
        int limit,
        boolean unclaimed
) {
    public VoucherSearchCriteria {
        int effectiveLimit = limit <= 0 ? 50 : limit;
        limit = effectiveLimit;
    }

    public static VoucherSearchCriteria empty() {
        return new VoucherSearchCriteria(null, null, null, null, 50, false);
    }
}
