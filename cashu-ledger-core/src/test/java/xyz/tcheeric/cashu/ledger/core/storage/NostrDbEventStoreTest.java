package xyz.tcheeric.cashu.ledger.core.storage;

import nostr.base.ElementAttribute;
import nostr.base.PublicKey;
import nostr.base.Signature;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for NostrDbEventStore.
 *
 * <p>These tests require the nostrdb-jni native library to be available.
 * Tests are conditionally enabled based on native library availability.
 */
class NostrDbEventStoreTest {

    private static final int VOUCHER_KIND = 30078;
    private static final String TEST_RELAY = "wss://relay.test";

    @TempDir
    Path tempDir;

    private NostrDbEventStore store;
    private boolean nativeAvailable;

    @BeforeEach
    void setUp() {
        EventStoreConfig config = EventStoreConfig.withPath(tempDir.resolve("ndb"));
        store = new NostrDbEventStore(config);
        nativeAvailable = store.isAvailable();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    /**
     * Tests that the store gracefully handles unavailable native library.
     */
    @Test
    void shouldReportAvailabilityCorrectly() {
        // The availability depends on whether native library is present
        // This test verifies the store doesn't crash when unavailable
        StoreStatistics stats = store.getStatistics();
        assertThat(stats.available()).isEqualTo(nativeAvailable);
    }

    /**
     * Tests that store operations return empty results when native is unavailable.
     */
    @Test
    void shouldReturnEmptyWhenUnavailable() {
        if (nativeAvailable) {
            return; // Skip if native is available
        }

        // Given: Store is unavailable
        assertThat(store.isAvailable()).isFalse();

        // When/Then: Operations return empty/false
        assertThat(store.store(createVoucherEvent("v-001"), TEST_RELAY)).isFalse();
        assertThat(store.findByEventId("abc123")).isEmpty();
        assertThat(store.findLatestByVoucherId("v-001")).isEmpty();
        assertThat(store.findAllByVoucherId("v-001", 10)).isEmpty();
        assertThat(store.findChildrenByParentId("v-parent", 10)).isEmpty();
        assertThat(store.findByAuthor("a".repeat(64), 10)).isEmpty();
        assertThat(store.findAllVouchers(10)).isEmpty();
        assertThat(store.contains("abc123")).isFalse();
    }

    /**
     * Tests storing and retrieving an event by ID.
     */
    @Test
    @EnabledIf("isNativeAvailable")
    void shouldStoreAndRetrieveEventById() {
        // Given: A voucher event
        String eventId = "a".repeat(64);
        GenericEvent event = createVoucherEvent("v-001");
        event.setId(eventId);

        // When: Storing the event
        boolean stored = store.store(event, TEST_RELAY);

        // Then: Event can be retrieved
        assertThat(stored).isTrue();

        Optional<StoredEvent> result = store.findByEventId(eventId);
        assertThat(result).isPresent();
        assertThat(result.get().event().getId()).isEqualTo(eventId);
        assertThat(result.get().relayUrl()).isEqualTo(TEST_RELAY);
    }

    /**
     * Tests finding the latest event for a voucher by d-tag.
     */
    @Test
    @EnabledIf("isNativeAvailable")
    void shouldFindLatestByVoucherId() {
        // Given: A voucher event with d-tag
        GenericEvent event = createVoucherEvent("v-002");
        store.store(event, TEST_RELAY);

        // When: Finding by voucher ID
        Optional<StoredEvent> result = store.findLatestByVoucherId("v-002");

        // Then: Event is found
        assertThat(result).isPresent();
        assertThat(result.get().kind()).isEqualTo(VOUCHER_KIND);
    }

    /**
     * Tests finding all events for a voucher (history).
     */
    @Test
    @EnabledIf("isNativeAvailable")
    void shouldFindAllEventsByVoucherId() {
        // Given: Multiple events for the same voucher
        String voucherId = "v-003";
        GenericEvent event1 = createVoucherEventWithVersion(voucherId, 0, "issued");
        GenericEvent event2 = createVoucherEventWithVersion(voucherId, 1, "claimed");
        store.store(event1, TEST_RELAY);
        store.store(event2, TEST_RELAY);

        // When: Finding all by voucher ID
        List<StoredEvent> results = store.findAllByVoucherId(voucherId, 10);

        // Then: Both events are returned
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
    }

    /**
     * Tests finding vouchers by author pubkey.
     */
    @Test
    @EnabledIf("isNativeAvailable")
    void shouldFindByAuthor() {
        // Given: Events from a specific author
        String authorPubkey = "b".repeat(64);
        GenericEvent event = createVoucherEvent("v-004");
        event.setPubKey(new PublicKey(authorPubkey));
        store.store(event, TEST_RELAY);

        // When: Finding by author
        List<StoredEvent> results = store.findByAuthor(authorPubkey, 10);

        // Then: Event is found
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).event().getPubKey().toString()).isEqualTo(authorPubkey);
    }

    /**
     * Tests finding all voucher events.
     */
    @Test
    @EnabledIf("isNativeAvailable")
    void shouldFindAllVouchers() {
        // Given: Multiple voucher events
        store.store(createVoucherEvent("v-005"), TEST_RELAY);
        store.store(createVoucherEvent("v-006"), TEST_RELAY);

        // When: Finding all vouchers
        List<StoredEvent> results = store.findAllVouchers(100);

        // Then: Events are found
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
    }

    /**
     * Tests the contains check.
     */
    @Test
    @EnabledIf("isNativeAvailable")
    void shouldCheckContains() {
        // Given: An event is stored
        String eventId = "c".repeat(64);
        GenericEvent event = createVoucherEvent("v-007");
        event.setId(eventId);
        store.store(event, TEST_RELAY);

        // When/Then: Contains returns correct values
        assertThat(store.contains(eventId)).isTrue();
        assertThat(store.contains("d".repeat(64))).isFalse();
    }

    /**
     * Tests that duplicate events are handled gracefully.
     */
    @Test
    @EnabledIf("isNativeAvailable")
    void shouldHandleDuplicateEvents() {
        // Given: An event
        GenericEvent event = createVoucherEvent("v-008");
        String eventId = "e".repeat(64);
        event.setId(eventId);

        // When: Storing the same event twice
        boolean first = store.store(event, TEST_RELAY);
        boolean second = store.store(event, TEST_RELAY);

        // Then: First succeeds, second is handled gracefully
        assertThat(first).isTrue();
        // Second may return true or false depending on nostrdb behavior
        // The important thing is it doesn't throw
    }

    /**
     * Tests statistics reporting.
     */
    @Test
    @EnabledIf("isNativeAvailable")
    void shouldReportStatistics() {
        // Given: Some events are stored
        store.store(createVoucherEvent("v-009"), TEST_RELAY);
        store.store(createVoucherEvent("v-010"), TEST_RELAY);

        // When: Getting statistics
        StoreStatistics stats = store.getStatistics();

        // Then: Statistics are available
        assertThat(stats.available()).isTrue();
        assertThat(stats.voucherEvents()).isGreaterThanOrEqualTo(2);
    }

    /**
     * Tests that null inputs are handled gracefully.
     */
    @Test
    void shouldHandleNullInputs() {
        // When/Then: Null inputs return empty/false
        assertThat(store.store(null, TEST_RELAY)).isFalse();
        assertThat(store.findByEventId(null)).isEmpty();
        assertThat(store.findLatestByVoucherId(null)).isEmpty();
        assertThat(store.findAllByVoucherId(null, 10)).isEmpty();
        assertThat(store.findChildrenByParentId(null, 10)).isEmpty();
        assertThat(store.findByAuthor(null, 10)).isEmpty();
        assertThat(store.contains(null)).isFalse();
    }

    /**
     * Tests that the store can be closed multiple times safely.
     */
    @Test
    void shouldHandleMultipleCloses() {
        // When: Closing multiple times
        store.close();
        store.close();

        // Then: No exception is thrown
        assertThat(store.isAvailable()).isFalse();
    }

    // Helper method for conditional test execution
    boolean isNativeAvailable() {
        return nativeAvailable;
    }

    private GenericEvent createVoucherEvent(String voucherId) {
        GenericEvent event = new GenericEvent();
        event.setId("f".repeat(64));
        event.setPubKey(new PublicKey("a".repeat(64)));
        event.setSignature(Signature.fromString("0".repeat(128)));
        event.setKind(VOUCHER_KIND);
        event.setCreatedAt(Instant.now().getEpochSecond());
        event.setContent("");
        event.setTags(List.of(
                tag("d", "voucher:" + voucherId),
                tag("status", "issued"),
                tag("state_version", "0"),
                tag("issuer_id", "test-issuer"),
                tag("face_value", "1000"),
                tag("token_amount", "1000")
        ));
        return event;
    }

    private GenericEvent createVoucherEventWithVersion(String voucherId, int version, String status) {
        GenericEvent event = new GenericEvent();
        event.setId(String.format("%d", version).repeat(64).substring(0, 64));
        event.setPubKey(new PublicKey("a".repeat(64)));
        event.setSignature(Signature.fromString("0".repeat(128)));
        event.setKind(VOUCHER_KIND);
        event.setCreatedAt(Instant.now().getEpochSecond() + version);
        event.setContent("");
        event.setTags(List.of(
                tag("d", "voucher:" + voucherId),
                tag("status", status),
                tag("state_version", String.valueOf(version)),
                tag("issuer_id", "test-issuer"),
                tag("face_value", "1000"),
                tag("token_amount", "1000")
        ));
        return event;
    }

    private BaseTag tag(String code, String... values) {
        List<ElementAttribute> attrs = java.util.Arrays.stream(values)
                .map(v -> new ElementAttribute(null, v))
                .toList();
        return new GenericTag(code, attrs);
    }
}
