package xyz.tcheeric.cashu.ledger.core.mapper;

import nostr.base.ElementAttribute;
import nostr.base.PublicKey;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherEventMapperTest {

    /**
     * Verifies that a full voucher event is mapped into a VoucherNode with all key fields populated.
     */
    @Test
    void shouldMapVoucherFieldsFromEvent() {
        // Arrange
        GenericEvent event = new GenericEvent();
        event.setId("c".repeat(64));
        event.setPubKey(new PublicKey("a".repeat(64)));
        event.setKind(30078);
        event.setCreatedAt(Instant.parse("2024-12-12T10:15:30Z").getEpochSecond());
        event.setContent("Sample memo");
        event.setTags(List.of(
                tag("d", "v-001"),
                tag("status", "issued"),
                tag("issuer_id", "merchant-1"),
                tag("face_value", "1500"),
                tag("unit", "EUR"),
                tag("decimals", "2"),
                tag("token_amount", "1500"),
                tag("backing_strategy", "PROPORTIONAL"),
                tag("issuance_ratio", "1.0"),
                tag("expires_at", Long.toString(Instant.parse("2024-12-31T00:00:00Z").getEpochSecond())),
                tag("parent", "v-parent", "1500", "1500")
        ));

        VoucherEventMapper mapper = new VoucherEventMapper();

        // Act
        Optional<VoucherNode> result = mapper.toVoucher(event, "wss://relay.example");

        // Assert
        assertThat(result).isPresent();
        VoucherNode node = result.orElseThrow();
        assertThat(node.voucherId()).isEqualTo("v-001");
        assertThat(node.status()).isEqualTo("issued");
        assertThat(node.faceValue()).isEqualTo(1500);
        assertThat(node.tokenAmount()).isEqualTo(1500);
        assertThat(node.unit()).isEqualTo("EUR");
        assertThat(node.backingStrategy().name()).isEqualTo("PROPORTIONAL");
        assertThat(node.parentContributions()).hasSize(1);
        assertThat(node.parentContributions().get(0).parentVoucherId()).isEqualTo("v-parent");
        assertThat(node.eventMetadata().relay()).isEqualTo("wss://relay.example");
    }

    private BaseTag tag(String code, String... values) {
        List<ElementAttribute> attrs = java.util.Arrays.stream(values)
                .map(v -> new ElementAttribute(null, v))
                .toList();
        return new GenericTag(code, attrs);
    }
}
