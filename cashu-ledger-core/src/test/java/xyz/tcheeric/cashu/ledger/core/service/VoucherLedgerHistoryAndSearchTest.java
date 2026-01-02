package xyz.tcheeric.cashu.ledger.core.service;

import nostr.base.ElementAttribute;
import nostr.base.PublicKey;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;
import xyz.tcheeric.cashu.ledger.core.relay.RelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.state.StatusChange;
import xyz.tcheeric.cashu.ledger.core.state.VoucherSearchCriteria;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherLedgerHistoryAndSearchTest {

    /**
     * Verifies that history returns multiple status events ordered by state version.
     */
    @Test
    void shouldReturnOrderedHistory() {
        // Given
        GenericEvent issued = voucherEvent("v-abc", "issued", 0, "evt-1", "issuer", Instant.parse("2025-01-01T00:00:00Z"));
        GenericEvent claimed = voucherEvent("v-abc", "claimed", 1, "evt-2", "recipient", Instant.parse("2025-01-01T00:10:00Z"));
        StubRelay relay = new StubRelay(List.of(issued, claimed));
        VoucherLedgerService service = new VoucherLedgerServiceImpl(
                relay,
                List.of("wss://relay.test"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)
        );

        // When
        var historyResult = service.fetchHistory(
                "v-abc",
                Instant.parse("2024-12-31T00:00:00Z"),
                Instant.parse("2025-01-02T00:00:00Z"),
                10
        );

        // Then
        assertThat(historyResult.events()).hasSize(2);
        assertThat(historyResult.events().get(0).stateVersion()).isEqualTo(0);
        assertThat(historyResult.events().get(1).stateVersion()).isEqualTo(1);
    }

    /**
     * Ensures search filters by issuer and status.
     */
    @Test
    void shouldFilterSearchByIssuerAndStatus() {
        // Given
        GenericEvent voucherA = voucherEvent("v-a", "issued", 0, "evt-a", "issuer-1", Instant.parse("2025-01-01T00:00:00Z"));
        GenericEvent voucherBIssued = voucherEvent("v-b", "issued", 0, "evt-b0", "issuer-2", Instant.parse("2025-01-01T00:01:00Z"));
        GenericEvent voucherB = voucherEvent("v-b", "claimed", 1, "evt-b", "issuer-2", Instant.parse("2025-01-01T00:05:00Z"));
        StubRelay relay = new StubRelay(List.of(voucherA, voucherBIssued, voucherB));
        VoucherLedgerService service = new VoucherLedgerServiceImpl(
                relay,
                List.of("wss://relay.test"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)
        );

        VoucherSearchCriteria criteria = new VoucherSearchCriteria(
                "issuer-2",
                VoucherStatus.CLAIMED,
                null,
                null,
                10,
                false
        );

        // When
        List<VoucherNode> results = service.search(criteria);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().voucherId()).isEqualTo("v-b");
    }

    private GenericEvent voucherEvent(String id, String status, long version, String eventId, String issuerId, Instant createdAt) {
        GenericEvent event = new GenericEvent();
        event.setId(padHex(eventId));
        event.setPubKey(new PublicKey("a".repeat(64)));
        event.setKind(30078);
        event.setCreatedAt(createdAt.getEpochSecond());
        List<ElementAttribute> dAttrs = List.of(new ElementAttribute(null, id));
        List<ElementAttribute> statusAttrs = List.of(new ElementAttribute(null, status));
        List<ElementAttribute> prevAttrs = version == 0
                ? List.of(new ElementAttribute(null, "unknown"))
                : List.of(new ElementAttribute(null, "issued"));
        List<ElementAttribute> versionAttrs = List.of(new ElementAttribute(null, Long.toString(version)));
        List<ElementAttribute> issuerAttrs = List.of(new ElementAttribute(null, issuerId));
        List<nostr.event.BaseTag> tags = new ArrayList<>();
        tags.add(new GenericTag("d", dAttrs));
        tags.add(new GenericTag("status", statusAttrs));
        tags.add(new GenericTag("previous_status", prevAttrs));
        tags.add(new GenericTag("state_version", versionAttrs));
        tags.add(new GenericTag("issuer_id", issuerAttrs));
        if ("claimed".equals(status)) {
            List<ElementAttribute> claimedBy = List.of(new ElementAttribute(null, "npub1recipient"));
            List<ElementAttribute> claimedAt = List.of(new ElementAttribute(null, Long.toString(createdAt.getEpochSecond())));
            tags.add(new GenericTag("claimed_by", claimedBy));
            tags.add(new GenericTag("claimed_at", claimedAt));
        }
        event.setTags(tags);
        return event;
    }

    private String padHex(String base) {
        String hex = base.replaceAll("[^a-f0-9]", "a");
        if (hex.length() >= 64) {
            return hex.substring(0, 64);
        }
        return (hex + "a".repeat(64)).substring(0, 64);
    }

    private static final class StubRelay implements RelayConnectionManager {
        private final List<GenericEvent> events;

        StubRelay(List<GenericEvent> events) {
            this.events = events;
        }

        @Override
        public void connect(List<String> relayUrls, Duration timeout) {
            // no-op
        }

        @Override
        public Optional<RelayEvent> fetchVoucher(String voucherId) {
            return events.stream()
                    .filter(evt -> evt.getTags().stream().anyMatch(tag -> tag instanceof GenericTag g
                            && "d".equals(g.getCode())
                            && g.getAttributes().getFirst().value().equals(voucherId)))
                    .findFirst()
                    .map(evt -> new RelayEvent(evt, "wss://relay.test"));
        }

        @Override
        public List<RelayEvent> fetchVouchersBatch(java.util.Collection<String> voucherIds) {
            return events.stream()
                    .filter(evt -> evt.getTags().stream().anyMatch(tag -> tag instanceof GenericTag g
                            && "d".equals(g.getCode())
                            && voucherIds.contains(g.getAttributes().getFirst().value())))
                    .map(evt -> new RelayEvent(evt, "wss://relay.test"))
                    .toList();
        }

        @Override
        public List<RelayEvent> searchChildren(String parentVoucherId, int limit) {
            return List.of();
        }

        @Override
        public List<RelayEvent> fetchVoucherEvents(String voucherId, int limit) {
            return events.stream()
                    .filter(evt -> evt.getTags().stream().anyMatch(tag -> tag instanceof GenericTag g
                            && "d".equals(g.getCode())
                            && g.getAttributes().getFirst().value().equals(voucherId)))
                    .limit(limit)
                    .map(evt -> new RelayEvent(evt, "wss://relay.test"))
                    .toList();
        }

        @Override
        public List<RelayEvent> searchVouchers(int limit) {
            return events.stream()
                    .limit(limit)
                    .map(evt -> new RelayEvent(evt, "wss://relay.test"))
                    .toList();
        }

        @Override
        public void disconnect() {
            // no-op
        }
    }
}
