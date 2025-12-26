package xyz.tcheeric.cashu.ledger.core.model;

/**
 * Contribution from a parent voucher used to create a child voucher.
 *
 * @param parentVoucherId       identifier of the parent voucher
 * @param contributedTokenAmount amount of backing tokens contributed
 * @param contributedFaceValue   face value contributed in minor units
 */
public record ParentContribution(
        String parentVoucherId,
        long contributedTokenAmount,
        long contributedFaceValue
) {
}
