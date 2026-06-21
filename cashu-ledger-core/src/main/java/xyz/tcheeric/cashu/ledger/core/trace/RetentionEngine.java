package xyz.tcheeric.cashu.ledger.core.trace;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the retention policy (design §5.11, FR-025/SC-011) on top of the tombstone primitive.
 * Two modes run per pass:
 *
 * <ul>
 *   <li><b>Age-based</b> — events whose {@code transition_at} predates the retention window are
 *       pruned (raw payload removed, join keys retained as a tombstone).</li>
 *   <li><b>Terminal sub-DAG</b> — terminal events (per the §5.4.1 activity cache) older than a
 *       shorter window are pruned ahead of the age threshold, since terminal tails have minimal
 *       forensic value. Configurable, and paused when the activity cache is stale to avoid
 *       over-pruning sub-DAGs that only look terminal because the cache is behind.</li>
 * </ul>
 *
 * <p>Pruning is irreversible; operators archive raw events off-system if they need recovery.
 * Each pass logs {@code prune_completed} with batch counts.</p>
 */
public final class RetentionEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionEngine.class);

    private final IndexedTraceEventStore store;
    private final SqliteSidecarIndex index;
    private final TombstoneStore tombstones;
    private final Duration ageWindow;
    private final Duration terminalWindow;
    private final boolean subDagEnabled;
    private final BooleanSupplier activityCacheStale;
    private final int batchLimit;
    private final Clock clock;

    public RetentionEngine(IndexedTraceEventStore store, SqliteSidecarIndex index,
                           TombstoneStore tombstones, Duration ageWindow, Duration terminalWindow,
                           boolean subDagEnabled, BooleanSupplier activityCacheStale,
                           int batchLimit, Clock clock) {
        this.store = store;
        this.index = index;
        this.tombstones = tombstones;
        this.ageWindow = ageWindow;
        this.terminalWindow = terminalWindow;
        this.subDagEnabled = subDagEnabled;
        this.activityCacheStale = activityCacheStale;
        this.batchLimit = batchLimit;
        this.clock = clock;
    }

    /** Runs one retention pass: terminal sub-DAG pruning (if enabled) then age-based pruning. */
    public PruneResult runOnce() {
        long now = Instant.now(clock).toEpochMilli();
        int terminalPruned = 0;
        boolean paused = false;

        if (subDagEnabled) {
            if (activityCacheStale.getAsBoolean()) {
                paused = true;
                LOGGER.warn("subdag_pruning_paused reason=activity_cache_stale");
            } else {
                terminalPruned = prune(index.terminalPruneCandidates(
                        now - terminalWindow.toMillis(), batchLimit));
            }
        }

        int agePruned = prune(index.pruneCandidatesByAge(now - ageWindow.toMillis(), batchLimit));

        LOGGER.info("prune_completed age_pruned={} terminal_pruned={} subdag_paused={}",
                agePruned, terminalPruned, paused);
        return new PruneResult(agePruned, terminalPruned, paused);
    }

    private int prune(List<String> eventIds) {
        int pruned = 0;
        for (String eventId : eventIds) {
            tombstones.prune(eventId);
            pruned++;
        }
        return pruned;
    }
}
