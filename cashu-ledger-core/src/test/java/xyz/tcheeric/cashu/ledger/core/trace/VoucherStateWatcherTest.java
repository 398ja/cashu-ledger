package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VoucherStateWatcher}: parsing kind-30078 events, recording the
 * latest revision, firing the terminal listener once, advancing the relay cursor, and
 * reporting ingest lag.
 */
class VoucherStateWatcherTest {

    private static final String RELAY = "wss://relay.imani.casa";

    private SqliteSidecarIndex index;
    private List<VoucherStatusObservation> terminalEvents;
    private VoucherStateWatcher watcher;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        terminalEvents = new ArrayList<>();
        watcher = new VoucherStateWatcher(index, terminalEvents::add);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    /** A non-terminal status is recorded without firing the terminal listener. */
    @Test
    void shouldRecordNonTerminalStatusWithoutNotifying() {
        // Given: a claimed (non-terminal) voucher event
        // When: the message is handled
        watcher.handleMessage(event("v1", "claimed", 1, 1_700_000_000L), RELAY);

        // Then: status stored, listener untouched
        assertThat(terminalEvents).isEmpty();
        assertThat(index.loadVoucherStatus("v1")).get()
                .extracting(VoucherStatusObservation::status).isEqualTo("claimed");
    }

    /** A terminal status fires the listener exactly once on first arrival. */
    @Test
    void shouldNotifyOnceWhenVoucherFirstBecomesTerminal() {
        // Given: a voucher that claims then is redeemed (terminal)
        watcher.handleMessage(event("v1", "claimed", 1, 1_700_000_000L), RELAY);

        // When: the terminal revision arrives, then a duplicate replay
        watcher.handleMessage(event("v1", "redeemed", 2, 1_700_000_100L), RELAY);
        watcher.handleMessage(event("v1", "redeemed", 2, 1_700_000_100L), RELAY);

        // Then: listener fired once for the terminal transition
        assertThat(terminalEvents).hasSize(1);
        assertThat(terminalEvents.get(0).voucherId()).isEqualTo("v1");
        assertThat(terminalEvents.get(0).terminal()).isTrue();
    }

    /** A stale revision (lower state_version) does not overwrite the latest status. */
    @Test
    void shouldIgnoreStaleRevision() {
        // Given: the latest revision is state_version 2
        watcher.handleMessage(event("v1", "redeemed", 2, 1_700_000_100L), RELAY);

        // When: an out-of-order older revision arrives
        watcher.handleMessage(event("v1", "claimed", 1, 1_700_000_000L), RELAY);

        // Then: the newer terminal status survives
        assertThat(index.loadVoucherStatus("v1")).get()
                .extracting(VoucherStatusObservation::status).isEqualTo("redeemed");
    }

    /** The relay cursor advances to the newest created_at seen. */
    @Test
    void shouldAdvanceCursorToNewestCreatedAt() {
        // Given/When: two events handled, newest last
        watcher.handleMessage(event("v1", "issued", 1, 1_700_000_000L), RELAY);
        watcher.handleMessage(event("v2", "issued", 1, 1_700_000_500L), RELAY);

        // Then: cursor reflects the latest created_at
        assertThat(index.loadCursor("voucher_watcher:" + RELAY)).contains(1_700_000_500L);
    }

    /** Lag is the gap between now and the newest event's created_at. */
    @Test
    void shouldReportLagFromNewestEvent() {
        // Given: an event at a known created_at
        watcher.handleMessage(event("v1", "issued", 1, 1_700_000_000L), RELAY);

        // When: measuring lag 60s later
        long lag = watcher.lagSeconds(Instant.ofEpochSecond(1_700_000_060L));

        // Then: lag is 60 seconds
        assertThat(lag).isEqualTo(60);
    }

    private static String event(String voucherId, String status, long version, long createdAt) {
        return "[\"EVENT\",\"sub\",{"
                + "\"kind\":30078,"
                + "\"created_at\":" + createdAt + ","
                + "\"tags\":["
                + "[\"d\",\"voucher:" + voucherId + "\"],"
                + "[\"status\",\"" + status + "\"],"
                + "[\"state_version\",\"" + version + "\"],"
                + "[\"issuer_id\",\"iss-1\"]"
                + "]}]";
    }
}
