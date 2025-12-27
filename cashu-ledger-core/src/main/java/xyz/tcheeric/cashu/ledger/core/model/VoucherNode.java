package xyz.tcheeric.cashu.ledger.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Core representation of a voucher returned from the ledger.
 */
public record VoucherNode(
        String voucherId,
        String issuerId,
        String issuerPublicKey,
        long faceValue,
        int faceDecimals,
        long tokenAmount,
        long originalFaceValue,
        long originalTokenAmount,
        String unit,
        BackingStrategy backingStrategy,
        BigDecimal issuanceRatio,
        VoucherStatus status,
        VoucherStateMetadata stateMetadata,
        Instant issuedAt,
        Instant expiresAt,
        String memo,
        Map<String, Object> merchantMetadata,
        List<ParentContribution> parentContributions,
        NostrEventMetadata eventMetadata
) {
}
