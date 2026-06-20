package xyz.tcheeric.cashu.ledger.web.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.ledger.core.trace.ActivityCache;
import xyz.tcheeric.cashu.ledger.core.trace.ProducerAttestationConfig;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex;
import xyz.tcheeric.cashu.ledger.core.trace.TraceEventMapper;
import xyz.tcheeric.cashu.ledger.core.trace.TraceEventSummary;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestListener;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestService;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestValidator;
import xyz.tcheeric.cashu.ledger.core.trace.TraceSyncEngine;
import xyz.tcheeric.cashu.ledger.core.trace.VoucherStateWatcher;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.web.trace.TraceStreamBroadcaster;

/**
 * Wires the ledger ingest path (design §6.1), active only when {@code trace.ingest.enabled=true}
 * so read-only deployments and tests never open relay connections. On startup it subscribes to
 * the configured relays: the sync engine streams kind-9079 events into the validating ingest
 * service, and the voucher-state watcher streams kind-30078 transitions into the activity cache.
 * Each newly stored event re-evaluates the activity cache and fans out a secret-free live
 * notification over SSE.
 */
@Configuration
@EnableConfigurationProperties(TraceIngestProperties.class)
@ConditionalOnProperty(prefix = "trace.ingest", name = "enabled", havingValue = "true")
public class TraceIngestConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceIngestConfig.class);

    @Bean
    public TraceIngestListener traceIngestListener(ActivityCache activityCache,
                                                   TraceStreamBroadcaster broadcaster) {
        return event -> {
            TransactionEvent e = event.event();
            activityCache.onEventIngested(e);
            broadcaster.publish(summary(e));
        };
    }

    @Bean
    public TraceIngestService traceIngestService(TraceEventStore store,
                                                 TraceIngestProperties properties,
                                                 TraceIngestListener listener) {
        TraceIngestValidator validator = new TraceIngestValidator(
                producerAttestation(properties), TransactionEvent.CURRENT_SCHEMA_VERSION,
                60, 24 * 60 * 60, System::currentTimeMillis);
        return new TraceIngestService(new TraceEventMapper(), validator, store,
                properties.isAllowHistorical(), listener);
    }

    @Bean(destroyMethod = "close")
    public TraceSyncEngine traceSyncEngine(TraceIngestService ingestService) {
        return new TraceSyncEngine(ingestService);
    }

    @Bean(destroyMethod = "close")
    public VoucherStateWatcher voucherStateWatcher(SqliteSidecarIndex index,
                                                   ActivityCache activityCache) {
        return new VoucherStateWatcher(index, activityCache);
    }

    @Bean
    public ApplicationRunner traceIngestSubscriber(TraceSyncEngine syncEngine,
                                                   VoucherStateWatcher watcher,
                                                   WebLedgerProperties ledgerProperties) {
        return args -> {
            for (String relay : ledgerProperties.getRelays()) {
                subscribeQuietly(relay, () -> syncEngine.subscribe(relay), "trace_sync");
                subscribeQuietly(relay, () -> watcher.subscribe(relay), "voucher_watch");
            }
            LOGGER.info("trace_ingest_started relays={}", ledgerProperties.getRelays().size());
        };
    }

    private static void subscribeQuietly(String relay, Runnable subscribe, String kind) {
        try {
            subscribe.run();
        } catch (RuntimeException e) {
            LOGGER.warn("trace_ingest_subscribe_failed kind={} relay={} error={}",
                    kind, relay, e.getMessage());
        }
    }

    private static ProducerAttestationConfig producerAttestation(TraceIngestProperties properties) {
        Map<String, Set<String>> byMint = new LinkedHashMap<>();
        for (TraceIngestProperties.Producer producer : properties.getProducers()) {
            byMint.computeIfAbsent(producer.getMintUrl(), key -> new LinkedHashSet<>())
                    .addAll(producer.getPubkeys());
        }
        return new ProducerAttestationConfig(byMint);
    }

    private static TraceEventSummary summary(TransactionEvent e) {
        boolean terminal = e.kind().isTerminalKind();
        return new TraceEventSummary(e.eventId().orElse(null), e.operationId(), e.kind().wireValue(),
                e.mintUrl(), e.transitionAt(), terminal ? "terminal" : "active",
                terminal ? "terminal_kind" : null);
    }
}
