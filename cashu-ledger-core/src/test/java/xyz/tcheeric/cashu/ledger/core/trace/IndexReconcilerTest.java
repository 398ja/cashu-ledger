package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Unit tests for {@link IndexReconciler}: HEALTHY when the sidecar matches the system of record,
 * LAGGED when the raw store holds events the sidecar has not yet projected.
 */
class IndexReconcilerTest {

    private InMemoryRawEventStore rawStore;
    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;

    @BeforeEach
    void setUp() {
        rawStore = new InMemoryRawEventStore();
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(rawStore, index);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    /** A synchronously-projected event leaves the index healthy with no backlog. */
    @Test
    void shouldReportHealthyWhenSidecarMatchesRaw() {
        store.store(StoredEvent.of(event("e1")));
        IndexReconciler reconciler = new IndexReconciler(store);

        IndexReconciler.Health health = reconciler.check();

        assertThat(health.status()).isEqualTo(IndexReconciler.Status.HEALTHY);
        assertThat(health.pendingEvents()).isZero();
        assertThat(health.isServable()).isTrue();
    }

    /** Raw events not yet in the sidecar (e.g. pre-rebuild) make the index lagged and unservable. */
    @Test
    void shouldReportLaggedWhenRawAheadOfSidecar() {
        // Given: an event written to the raw store but not projected to the sidecar
        rawStore.store(StoredEvent.of(event("e1")));
        IndexReconciler reconciler = new IndexReconciler(store);

        IndexReconciler.Health health = reconciler.check();

        assertThat(health.status()).isEqualTo(IndexReconciler.Status.LAGGED);
        assertThat(health.pendingEvents()).isEqualTo(1);
        assertThat(health.isServable()).isFalse();
    }

    private static TransactionEvent event(String id) {
        return new TransactionEvent(
                Optional.of(id), "op-" + id, OperationKind.SWAP, "https://mint.imani.casa", "sat",
                Instant.ofEpochSecond(1_700_000_000L), Instant.ofEpochSecond(1_700_000_000L), "pk",
                Optional.empty(),
                List.of(new ProofRef(64, "ks", "02ab", Optional.of("s"), Optional.of("c"),
                        Optional.empty(), Optional.empty())),
                List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(0L),
                Optional.empty(), Optional.empty(), Optional.empty(), PrivacyMode.FULL,
                Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(id), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(1_700_000_000L)));
    }
}
