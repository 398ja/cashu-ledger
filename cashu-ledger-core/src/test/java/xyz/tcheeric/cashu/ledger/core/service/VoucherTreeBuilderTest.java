package xyz.tcheeric.cashu.ledger.core.service;

import nostr.base.ElementAttribute;
import nostr.base.PublicKey;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.core.model.TraversalDirection;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;
import xyz.tcheeric.cashu.ledger.core.relay.RelayConnectionManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherTreeBuilderTest {

    /**
     * Builds a tree with parent and child nodes and verifies depth/children mapping.
     */
    @Test
    void shouldBuildTreeWithParentsAndChildren() {
        // Arrange
        GenericEvent parent = voucherEvent("p-1", List.of(
                tag("d", "p-1"),
                tag("status", "issued"),
                tag("face_value", "5000"),
                tag("token_amount", "5000")
        ));
        GenericEvent target = voucherEvent("t-1", List.of(
                tag("d", "t-1"),
                tag("status", "issued"),
                tag("face_value", "2000"),
                tag("token_amount", "2000"),
                tag("parent", "p-1", "2000", "2000")
        ));
        GenericEvent child = voucherEvent("c-1", List.of(
                tag("d", "c-1"),
                tag("status", "issued"),
                tag("face_value", "1000"),
                tag("token_amount", "1000"),
                tag("parent", "t-1", "1000", "1000")
        ));

        StubRelayConnectionManager relay = new StubRelayConnectionManager(parent, target, child);
        VoucherLedgerService service = new VoucherLedgerServiceImpl(
                relay,
                List.of("wss://relay.test"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );

        // Act
        Optional<VoucherTree> tree = service.buildTree("t-1", 5, TraversalDirection.BOTH);

        // Assert
        assertThat(tree).isPresent();
        VoucherTree built = tree.orElseThrow();
        assertThat(built.nodes()).hasSize(3);
        assertThat(built.childrenMap().get("t-1")).containsExactly("c-1");
        assertThat(built.childrenMap().get("p-1")).containsExactly("t-1");
        assertThat(built.root().voucherId()).isEqualTo("p-1");
        assertThat(built.depth()).isEqualTo(3);
    }

    /**
     * Ensures split children referenced via split_into are fetched and attached even when child events lack parent tags.
     */
    @Test
    void shouldAttachSplitChildrenUsingSplitIntoHints() {
        // Arrange
        GenericEvent splitParent = voucherEvent("sp-1", List.of(
                tag("d", "sp-1"),
                tag("status", "split"),
                tag("face_value", "3000"),
                tag("token_amount", "3000"),
                tag("split_into", "sp-child-1")
        ));
        GenericEvent splitChild = voucherEvent("sp-child-1", List.of(
                tag("d", "sp-child-1"),
                tag("status", "issued"),
                tag("face_value", "1000"),
                tag("token_amount", "1000")
        ));

        StubRelayConnectionManager relay = new StubRelayConnectionManager(splitParent, splitChild);
        VoucherLedgerService service = new VoucherLedgerServiceImpl(
                relay,
                List.of("wss://relay.test"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );

        // Act
        Optional<VoucherTree> tree = service.buildTree("sp-1", 5, TraversalDirection.DOWN);

        // Assert
        assertThat(tree).isPresent();
        VoucherTree built = tree.orElseThrow();
        assertThat(built.nodes()).containsKey("sp-child-1");
        assertThat(built.childrenMap().get("sp-1")).contains("sp-child-1");
    }

    private GenericEvent voucherEvent(String id, List<BaseTag> tags) {
        GenericEvent event = new GenericEvent();
        event.setId("e".repeat(64));
        event.setPubKey(new PublicKey("f".repeat(64)));
        event.setKind(30078);
        event.setTags(tags);
        return event;
    }

    private BaseTag tag(String code, String... values) {
        List<ElementAttribute> attrs = new ArrayList<>();
        for (String value : values) {
            attrs.add(new ElementAttribute(null, value));
        }
        return new GenericTag(code, attrs);
    }

    private static final class StubRelayConnectionManager implements RelayConnectionManager {
        private final Map<String, GenericEvent> events = new HashMap<>();

        StubRelayConnectionManager(GenericEvent... evts) {
            for (GenericEvent evt : evts) {
                String id = evt.getTags().stream()
                        .filter(tag -> tag instanceof GenericTag g && "d".equals(g.getCode()))
                        .findFirst()
                        .map(tag -> ((GenericTag) tag).getAttributes().getFirst().value().toString())
                        .orElseThrow();
                events.put(id, evt);
            }
        }

        @Override
        public void connect(List<String> relayUrls, Duration timeout) {
            // no-op
        }

        @Override
        public Optional<RelayEvent> fetchVoucher(String voucherId) {
            GenericEvent evt = events.get(voucherId);
            return evt == null ? Optional.empty() : Optional.of(new RelayEvent(evt, "wss://relay.test"));
        }

        @Override
        public List<RelayEvent> searchChildren(String parentVoucherId, int limit) {
            List<RelayEvent> found = new ArrayList<>();
            events.forEach((id, evt) -> {
                boolean hasParent = evt.getTags().stream().anyMatch(tag ->
                        tag instanceof GenericTag g
                                && "parent".equals(g.getCode())
                                && g.getAttributes() != null
                                && !g.getAttributes().isEmpty()
                                && parentVoucherId.equals(g.getAttributes().getFirst().value())
                );
                if (hasParent) {
                    found.add(new RelayEvent(evt, "wss://relay.test"));
                }
            });
            return found;
        }

        @Override
        public List<RelayEvent> fetchVoucherEvents(String voucherId, int limit) {
            GenericEvent evt = events.get(voucherId);
            return evt == null ? List.of() : List.of(new RelayEvent(evt, "wss://relay.test"));
        }

        @Override
        public List<RelayEvent> searchVouchers(int limit) {
            return events.values().stream()
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
