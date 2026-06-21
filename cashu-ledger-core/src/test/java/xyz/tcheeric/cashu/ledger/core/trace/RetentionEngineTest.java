package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
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
 * Unit tests for {@link RetentionEngine}: age-based pruning, terminal sub-DAG pruning ahead of
 * the age threshold, and pausing sub-DAG pruning when the activity cache is stale.
 */
class RetentionEngineTest {

    private static final String MINT = "https://mint.imani.casa";
    private static final Instant NOW = Instant.parse("2026-06-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SqliteSidecarIndex index;
    private IndexedTraceEventStore store;
    private TombstoneStore tombstones;

    @BeforeEach
    void setUp() {
        index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
        store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
        tombstones = new TombstoneStore(store);
        // e-old: SWAP 40 days ago (past age window); e-term: MELT 2 days ago (terminal, past
        // terminal window only); e-active: SWAP 2 days ago; e-recent: SWAP 1 hour ago.
        store.store(StoredEvent.of(event("e-old", OperationKind.SWAP, NOW.minus(Duration.ofDays(40)))));
        store.store(StoredEvent.of(event("e-term", OperationKind.MELT, NOW.minus(Duration.ofDays(2)))));
        store.store(StoredEvent.of(event("e-active", OperationKind.SWAP, NOW.minus(Duration.ofDays(2)))));
        store.store(StoredEvent.of(event("e-recent", OperationKind.SWAP, NOW.minus(Duration.ofHours(1)))));
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    /** With sub-DAG pruning on, age prunes the old event and terminal prunes the terminal tail. */
    @Test
    void shouldPruneByAgeAndTerminalSubDag() {
        // Given: 30-day age window, 1-day terminal window, sub-DAG on, cache fresh
        RetentionEngine engine = engine(true, () -> false);

        // When: one pass
        PruneResult result = engine.runOnce();

        // Then: e-old (age) and e-term (terminal) pruned; e-active and e-recent retained
        assertThat(result.agePruned()).isEqualTo(1);
        assertThat(result.terminalPruned()).isEqualTo(1);
        assertThat(result.subDagPaused()).isFalse();
        assertThat(index.isTombstoned("e-old")).isTrue();
        assertThat(index.isTombstoned("e-term")).isTrue();
        assertThat(index.isTombstoned("e-active")).isFalse();
        assertThat(index.isTombstoned("e-recent")).isFalse();
    }

    /** A stale activity cache pauses sub-DAG pruning; only age-based pruning runs. */
    @Test
    void shouldPauseSubDagPruningWhenCacheStale() {
        // Given: sub-DAG on but the activity cache reports stale
        RetentionEngine engine = engine(true, () -> true);

        // When: one pass
        PruneResult result = engine.runOnce();

        // Then: only e-old pruned; the terminal tail is left intact and the pause is reported
        assertThat(result.agePruned()).isEqualTo(1);
        assertThat(result.terminalPruned()).isZero();
        assertThat(result.subDagPaused()).isTrue();
        assertThat(index.isTombstoned("e-term")).isFalse();
    }

    private RetentionEngine engine(boolean subDag, java.util.function.BooleanSupplier stale) {
        return new RetentionEngine(store, index, tombstones, Duration.ofDays(30), Duration.ofDays(1),
                subDag, stale, 100, CLOCK);
    }

    private static TransactionEvent event(String id, OperationKind kind, Instant at) {
        return new TransactionEvent(
                Optional.of(id), "op-" + id, kind, MINT, "sat", at,
                Instant.ofEpochSecond(at.getEpochSecond()), "pk", Optional.empty(),
                List.of(new ProofRef(64, "00ad12ef", "02" + id.hashCode(), Optional.of("s"),
                        Optional.of("c"), Optional.empty(), Optional.empty())),
                List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(0L),
                Optional.empty(), Optional.empty(), Optional.empty(), PrivacyMode.FULL,
                Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(id), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(at.getEpochSecond())));
    }
}
