package xyz.tcheeric.cashu.ledger.web.config;

import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.ledger.web.security.TraceIssuerProperties;
import xyz.tcheeric.cashu.ledger.web.security.TraceSecurityProperties;
import xyz.tcheeric.cashu.ledger.core.trace.ActivityCache;
import xyz.tcheeric.cashu.ledger.core.trace.RedactionKeyRegistry;
import xyz.tcheeric.cashu.ledger.core.trace.EdgeDeriver;
import xyz.tcheeric.cashu.ledger.core.trace.IndexedTraceEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.InMemoryRawEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.RawEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex;
import xyz.tcheeric.cashu.ledger.core.trace.TraceQueryService;
import xyz.tcheeric.cashu.ledger.core.trace.VisualisationService;
import xyz.tcheeric.cashu.ledger.core.trace.WalkService;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;

/**
 * Wires the trace read path into the web context: the SQLite sidecar, the raw-event
 * store, the indexed store, and the query service. The raw store is currently
 * in-memory; a nostrdb-backed implementation and the sync engine are wired in a
 * follow-up (this checkpoint covers the read API).
 */
@Configuration
@EnableConfigurationProperties(TraceIssuerProperties.class)
public class TraceWebConfig {

    @Bean(destroyMethod = "close")
    public SqliteSidecarIndex traceSidecarIndex(
            @Value("${trace.storage.sidecar-jdbc-url:jdbc:sqlite::memory:}") String jdbcUrl) {
        return new SqliteSidecarIndex(jdbcUrl);
    }

    @Bean
    public RawEventStore traceRawEventStore() {
        return new InMemoryRawEventStore();
    }

    @Bean
    public TraceEventStore traceEventStore(RawEventStore rawEventStore, SqliteSidecarIndex index) {
        return new IndexedTraceEventStore(rawEventStore, index);
    }

    @Bean
    public TraceQueryService traceQueryService(TraceEventStore store, SqliteSidecarIndex index) {
        return new TraceQueryService(store, index);
    }

    @Bean
    public EdgeDeriver traceEdgeDeriver(TraceEventStore store) {
        return new EdgeDeriver(store);
    }

    @Bean
    public WalkService traceWalkService(TraceEventStore store, EdgeDeriver edgeDeriver) {
        return new WalkService(store, edgeDeriver);
    }

    @Bean
    public VisualisationService traceVisualisationService() {
        return new VisualisationService();
    }

    @Bean
    public RedactionKeyRegistry redactionKeyRegistry(TraceSecurityProperties securityProperties) {
        return new RedactionKeyRegistry(HexFormat.of().parseHex(securityProperties.getRedactionMasterKeyHex()));
    }

    @Bean
    public ActivityCache traceActivityCache(SqliteSidecarIndex index) {
        return new ActivityCache(index);
    }
}
