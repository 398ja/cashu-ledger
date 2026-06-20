package xyz.tcheeric.cashu.ledger.web.config;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.tcheeric.cashu.ledger.core.trace.IndexedTraceEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.PruneResult;
import xyz.tcheeric.cashu.ledger.core.trace.RetentionEngine;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex;
import xyz.tcheeric.cashu.ledger.core.trace.TombstoneStore;

/**
 * Wires scheduled retention (design §5.11, T072 operationalised), active only when
 * {@code trace.retention.enabled=true}. Builds a {@link RetentionEngine} from
 * {@link TraceRetentionProperties} and runs it on a fixed delay.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(TraceRetentionProperties.class)
@ConditionalOnProperty(prefix = "trace.retention", name = "enabled", havingValue = "true")
public class TraceRetentionConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceRetentionConfig.class);

    @Bean
    public RetentionEngine traceRetentionEngine(IndexedTraceEventStore store, SqliteSidecarIndex index,
                                                TombstoneStore tombstones,
                                                TraceRetentionProperties properties) {
        // A real activity-cache staleness signal (e.g. voucher-watcher lag) can replace this;
        // until then the cache is treated as fresh so terminal sub-DAG pruning is allowed.
        return new RetentionEngine(store, index, tombstones, properties.getAgeWindow(),
                properties.getTerminalWindow(), properties.isTerminalSubdagEnabled(),
                () -> false, properties.getBatchLimit(), Clock.systemUTC());
    }

    @Bean
    public RetentionScheduler traceRetentionScheduler(RetentionEngine engine,
                                                      TraceRetentionProperties properties) {
        return new RetentionScheduler(engine);
    }

    /** Runs the retention engine on the configured fixed delay. */
    public static final class RetentionScheduler {

        private final RetentionEngine engine;

        RetentionScheduler(RetentionEngine engine) {
            this.engine = engine;
        }

        @Scheduled(fixedDelayString = "${trace.retention.sweep-delay:PT1H}")
        public void prune() {
            PruneResult result = engine.runOnce();
            if (result.total() > 0 || result.subDagPaused()) {
                LOGGER.info("retention_pass age_pruned={} terminal_pruned={} subdag_paused={}",
                        result.agePruned(), result.terminalPruned(), result.subDagPaused());
            }
        }
    }
}
