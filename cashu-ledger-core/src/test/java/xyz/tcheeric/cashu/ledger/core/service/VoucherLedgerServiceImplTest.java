package xyz.tcheeric.cashu.ledger.core.service;

import nostr.base.ElementAttribute;
import nostr.base.PublicKey;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.core.relay.RelayConnectionManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherLedgerServiceImplTest {

    /**
     * Ensures the service fetches a voucher and maps it using the relay connection manager.
     */
    @Test
    void shouldFetchVoucherFromRelay() {
        // Arrange
        GenericEvent event = new GenericEvent();
        event.setId("d".repeat(64));
        event.setPubKey(new PublicKey("b".repeat(64)));
        event.setKind(30078);
        event.setTags(List.of(
                new GenericTag("d", List.of(new ElementAttribute(null, "v-777"))),
                new GenericTag("status", List.of(new ElementAttribute(null, "issued")))
        ));

        StubRelayConnectionManager stubRelay = new StubRelayConnectionManager(event);
        VoucherLedgerService service = new VoucherLedgerServiceImpl(
                stubRelay,
                List.of("wss://relay.test"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)
        );

        // Act
        Optional<xyz.tcheeric.cashu.ledger.core.model.VoucherNode> result = service.fetchVoucher("v-777");

        // Assert
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().voucherId()).isEqualTo("v-777");
    }

    private static final class StubRelayConnectionManager implements RelayConnectionManager {
        private final GenericEvent event;

        private StubRelayConnectionManager(GenericEvent event) {
            this.event = event;
        }

        @Override
        public void connect(List<String> relayUrls, Duration timeout) {
            // no-op for stub
        }

        @Override
        public Optional<RelayEvent> fetchVoucher(String voucherId) {
            return Optional.of(new RelayEvent(event, "wss://relay.test"));
        }

        @Override
        public List<RelayEvent> searchChildren(String parentVoucherId, int limit) {
            return List.of();
        }

        @Override
        public void disconnect() {
            // no-op for stub
        }
    }
}
