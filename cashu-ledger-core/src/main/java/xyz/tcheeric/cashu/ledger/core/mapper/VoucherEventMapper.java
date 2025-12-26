package xyz.tcheeric.cashu.ledger.core.mapper;

import nostr.base.ElementAttribute;
import nostr.base.PublicKey;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import xyz.tcheeric.cashu.ledger.core.model.BackingStrategy;
import xyz.tcheeric.cashu.ledger.core.model.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.core.model.ParentContribution;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Maps nostr voucher events (kind 30078) to domain {@link VoucherNode}.
 */
public class VoucherEventMapper {

    public Optional<VoucherNode> toVoucher(GenericEvent event, String relayUrl) {
        if (event == null || event.getTags() == null) {
            return Optional.empty();
        }

        TagValues values = extractTagValues(event);
        if (values.voucherId == null || values.voucherId.isBlank()) {
            return Optional.empty();
        }

        Long createdAtEpoch = event.getCreatedAt();
        Instant createdAt = createdAtEpoch != null && createdAtEpoch > 0
                ? Instant.ofEpochSecond(createdAtEpoch)
                : null;
        Instant expiresAt = values.expiresAtEpoch != null ? Instant.ofEpochSecond(values.expiresAtEpoch) : null;

        NostrEventMetadata metadata = new NostrEventMetadata(
                event.getId(),
                toHex(event.getPubKey()),
                createdAt,
                relayUrl,
                event.getKind(),
                values.rawTags
        );

        VoucherNode node = new VoucherNode(
                values.voucherId,
                values.issuerId,
                toHex(event.getPubKey()),
                values.faceValue,
                values.decimals,
                values.tokenAmount,
                values.originalFaceValue,
                values.originalTokenAmount,
                values.unit,
                BackingStrategy.fromValue(values.backingStrategy),
                values.issuanceRatio,
                values.status,
                createdAt,
                expiresAt,
                values.memo,
                Collections.emptyMap(),
                values.parentContributions,
                metadata
        );

        return Optional.of(node);
    }

    private TagValues extractTagValues(GenericEvent event) {
        TagValues values = new TagValues();
        values.rawTags = new ArrayList<>();

        for (BaseTag tag : event.getTags()) {
            if (!(tag instanceof GenericTag genericTag)) {
                continue;
            }

            String code = genericTag.getCode();
            List<ElementAttribute> attributes = genericTag.getAttributes() != null
                    ? genericTag.getAttributes()
                    : List.of();
            List<String> rawList = new ArrayList<>();
            rawList.add(code);
            attributes.stream()
                    .map(ElementAttribute::value)
                    .map(Object::toString)
                    .forEach(rawList::add);
            values.rawTags.add(rawList);

            switch (code) {
                case "d" -> values.voucherId = attributeValue(attributes, 0);
                case "status" -> values.status = attributeValue(attributes, 0, "unknown");
                case "issuer_id" -> values.issuerId = attributeValue(attributes, 0);
                case "face_value" -> values.faceValue = parseLong(attributeValue(attributes, 0), 0L);
                case "unit" -> values.unit = attributeValue(attributes, 0);
                case "decimals" -> values.decimals = (int) parseLong(attributeValue(attributes, 0), 0L);
                case "token_amount" -> values.tokenAmount = parseLong(attributeValue(attributes, 0), 0L);
                case "backing_strategy" -> values.backingStrategy = attributeValue(attributes, 0);
                case "issuance_ratio" -> values.issuanceRatio = parseBigDecimal(attributeValue(attributes, 0));
                case "expires_at" -> values.expiresAtEpoch = parseLong(attributeValue(attributes, 0), null);
                case "parent" -> values.parentContributions.add(parseParent(attributes));
                default -> {
                    // ignore unknown tags for now
                }
            }
        }

        values.memo = event.getContent();
        values.originalFaceValue = values.originalFaceValue != 0 ? values.originalFaceValue : values.faceValue;
        values.originalTokenAmount = values.originalTokenAmount != 0 ? values.originalTokenAmount : values.tokenAmount;
        return values;
    }

    private ParentContribution parseParent(List<ElementAttribute> attributes) {
        String parentId = attributeValue(attributes, 0);
        long contributedTokens = parseLong(attributeValue(attributes, 1), 0L);
        long contributedFace = parseLong(attributeValue(attributes, 2), 0L);
        return new ParentContribution(parentId, contributedTokens, contributedFace);
    }

    private String attributeValue(List<ElementAttribute> attributes, int index) {
        return attributeValue(attributes, index, null);
    }

    private String attributeValue(List<ElementAttribute> attributes, int index, String defaultValue) {
        if (index < attributes.size()) {
            Object value = attributes.get(index).value();
            return value != null ? value.toString() : defaultValue;
        }
        return defaultValue;
    }

    private long parseLong(String value, Long defaultValue) {
        if (value == null) {
            return defaultValue != null ? defaultValue : 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return defaultValue != null ? defaultValue : 0L;
        }
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private String toHex(PublicKey publicKey) {
        if (publicKey == null) {
            return null;
        }
        return publicKey.toString();
    }

    private static final class TagValues {
        private String voucherId;
        private String issuerId;
        private String backingStrategy;
        private String unit;
        private String status = "unknown";
        private String memo;
        private long faceValue;
        private long originalFaceValue;
        private long tokenAmount;
        private long originalTokenAmount;
        private int decimals;
        private Long expiresAtEpoch;
        private BigDecimal issuanceRatio = BigDecimal.ZERO;
        private List<ParentContribution> parentContributions = new ArrayList<>();
        private List<List<String>> rawTags = new ArrayList<>();
    }
}
