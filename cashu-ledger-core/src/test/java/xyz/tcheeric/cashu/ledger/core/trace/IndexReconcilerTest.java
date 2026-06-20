package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Unit tests for {@link IndexReconciler}: classifying index health as healthy or lagged from the
 * newest indexed event's age.
 */
class IndexReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-06-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    /** A recently indexed event keeps the index healthy. */
    @Test
    void shouldReportHealthyWhenRecent() {
        store.store(StoredEvent.of(event("e1", NOW.minusSeconds(10))));
        IndexReconciler reconciler = new IndexReconciler(store, 60, CLOCK);

        IndexReconciler.Health health = reconciler.check();

        assertThat(health.status()).isEqualTo(IndexReconciler.Status.HEALTHY);
        assertThat(health.lagSeconds()).isEqualTo(10);
        assertThat(health.isServable()).isTrue();
    }

    /** An old newest-event marks the index lagged but still servable. */
    @Test
    void shouldReportLaggedWhenBehindThreshold() {
        store.store(StoredEvent.of(event("e1", NOW.minusSeconds(120))));
        IndexReconciler reconciler = new IndexReconciler(store, 60, CLOCK);

        IndexReconciler.Health health = reconciler.check();

        assertThat(health.status()).isEqualTo(IndexReconciler.Status.LAGGED);
        assertThat(health.lagSeconds()).isEqualTo(120);
        assertThat(health.isServable()).isTrue();
    }

    private static TransactionEvent event(String id, Instant at) {
        return new TransactionEvent(
                Optional.of(id), "op-" + id, OperationKind.SWAP, "https://mint.imani.casa", "sat",
                at, Instant.ofEpochSecond(at.getEpochSecond()), "pk", Optional.empty(),
                List.of(new ProofRef(64, "ks", "02ab", Optional.of("s"), Optional.of("c"),
                        Optional.empty(), Optional.empty())),
                List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(0L),
                Optional.empty(), Optional.empty(), Optional.empty(), PrivacyMode.FULL,
                Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(id), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(at.getEpochSecond())));
    }
}
