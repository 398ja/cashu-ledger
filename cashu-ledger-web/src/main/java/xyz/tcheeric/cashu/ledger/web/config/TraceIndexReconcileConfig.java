package xyz.tcheeric.cashu.ledger.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.tcheeric.cashu.ledger.core.trace.IndexReconciler;
import xyz.tcheeric.cashu.ledger.core.trace.NostrDbRawEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.RawEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex;

/**
 * Periodic {@code pending_index} reconciler (design §5.4, T030): when the sidecar is behind the
 * nostrdb system of record — a sidecar write that failed, or a window the startup rebuild missed —
 * it re-projects the surviving events. Active only with the durable nostrdb store, since
 * re-projection reads from the system of record.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "trace.storage", name = "nostrdb-enabled", havingValue = "true")
public class TraceIndexReconcileConfig {

    @Bean
    public PendingIndexReconciler pendingIndexReconciler(RawEventStore rawEventStore,
                                                         SqliteSidecarIndex index,
                                                         IndexReconciler indexReconciler) {
        return new PendingIndexReconciler(rawEventStore, index, indexReconciler);
    }

    /** Re-projects nostrdb into the sidecar on a fixed delay whenever a backlog is detected. */
    public static final class PendingIndexReconciler {

        private static final Logger LOGGER = LoggerFactory.getLogger(PendingIndexReconciler.class);

        private final RawEventStore rawEventStore;
        private final SqliteSidecarIndex index;
        private final IndexReconciler indexReconciler;

        PendingIndexReconciler(RawEventStore rawEventStore, SqliteSidecarIndex index,
                               IndexReconciler indexReconciler) {
            this.rawEventStore = rawEventStore;
            this.index = index;
            this.indexReconciler = indexReconciler;
        }

        @Scheduled(fixedDelayString = "${trace.storage.reconcile-delay:PT30S}")
        public void reconcile() {
            if (rawEventStore instanceof NostrDbRawEventStore nostrdb
                    && indexReconciler.pendingEvents() > 0) {
                int reindexed = nostrdb.reindex(index);
                LOGGER.info("pending_index_reconciled reindexed={}", reindexed);
            }
        }
    }
}
