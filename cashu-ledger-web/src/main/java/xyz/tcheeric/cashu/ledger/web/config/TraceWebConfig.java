package xyz.tcheeric.cashu.ledger.web.config;

import java.time.Clock;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.ledger.web.security.TraceIssuerProperties;
import xyz.tcheeric.cashu.ledger.web.security.TraceSecurityProperties;
import xyz.tcheeric.cashu.ledger.core.trace.ActivityCache;
import xyz.tcheeric.cashu.ledger.core.trace.RedactionKeyRegistry;
import xyz.tcheeric.cashu.ledger.core.trace.TombstoneStore;
import xyz.tcheeric.cashu.ledger.core.trace.EdgeDeriver;
import xyz.tcheeric.cashu.ledger.core.trace.IndexReconciler;
import xyz.tcheeric.cashu.ledger.core.trace.IndexedTraceEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.InMemoryRawEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.NostrDbRawEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.RawEventStore;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex;
import xyz.tcheeric.cashu.ledger.core.trace.QuoteStatusService;
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
@EnableConfigurationProperties({TraceIssuerProperties.class, TraceLimitsProperties.class})
public class TraceWebConfig {

    @Bean(destroyMethod = "close")
    public SqliteSidecarIndex traceSidecarIndex(
            @Value("${trace.storage.sidecar-jdbc-url:jdbc:sqlite::memory:}") String jdbcUrl) {
        SqliteSidecarIndex index = new SqliteSidecarIndex(jdbcUrl);
        restrictToOwner(sqlitePathOf(jdbcUrl));
        return index;
    }

    @Bean(destroyMethod = "close")

    @ConditionalOnProperty(prefix = "trace.storage", name = "nostrdb-enabled", havingValue = "true")
    public RawEventStore nostrdbRawEventStore(
            @Value("${trace.storage.nostrdb-path:${user.home}/.cashu-ledger/trace-ndb}") String path) {
        RawEventStore store = NostrDbRawEventStore.open(java.nio.file.Path.of(path));
        restrictToOwner(java.nio.file.Path.of(path));
        return store;
    }

    /**
     * The filesystem path behind a SQLite JDBC URL, or null for in-memory.
     */
    private static java.nio.file.Path sqlitePathOf(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:sqlite:")) {
            return null;
        }
        String path = jdbcUrl.substring("jdbc:sqlite:".length());
        if (path.isBlank() || path.startsWith(":")) {
            return null; // :memory: and friends
        }
        return java.nio.file.Path.of(path);
    }

    /**
     * Narrows a store to owner-only access.
     *
     * <p>These files hold the trace index and the raw events behind it, which is the ledger's
     * view of who transacted with whom. They were created with whatever the process umask
     * happened to be (audit L-29), which on a default Linux umask is world-readable. Anyone with
     * a shell on the host could read the graph without touching the API that authorises access
     * to it.
     *
     * <p>Best-effort by design: a non-POSIX filesystem, a bind mount with fixed ownership, or a
     * store that does not exist yet all make this impossible, and none of them is a reason to
     * refuse to start. The warning is what makes the gap visible.
     */
    private static void restrictToOwner(java.nio.file.Path path) {
        if (path == null || !java.nio.file.Files.exists(path)) {
            return;
        }
        try {
            java.nio.file.Files.setPosixFilePermissions(path,
                    java.util.EnumSet.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            LoggerFactory.getLogger(TraceWebConfig.class).warn(
                    "Could not restrict permissions on {}: it may be readable by other users on "
                            + "this host. Reason: {}", path, e.getMessage());
        }
    }

    @Bean
    @ConditionalOnMissingBean(RawEventStore.class)
    public RawEventStore inMemoryRawEventStore() {
        return new InMemoryRawEventStore();
    }

    @Bean
    public IndexedTraceEventStore traceEventStore(RawEventStore rawEventStore, SqliteSidecarIndex index) {
        return new IndexedTraceEventStore(rawEventStore, index);
    }

    /**
     * On startup, rebuild the (possibly in-memory) sidecar from the durable nostrdb store so the
     * index reflects the system of record after a restart (design §5.4 rebuild, T030).
     */
    @Bean
    public org.springframework.boot.ApplicationRunner traceSidecarRebuild(
            RawEventStore rawEventStore, SqliteSidecarIndex index) {
        return args -> {
            if (rawEventStore instanceof NostrDbRawEventStore nostrdb && index.count() < nostrdb.count()) {
                nostrdb.reindex(index);
            }
        };
    }

    @Bean
    public TombstoneStore traceTombstoneStore(IndexedTraceEventStore store) {
        return new TombstoneStore(store);
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
    public WalkService traceWalkService(TraceEventStore store, EdgeDeriver edgeDeriver,
                                        SqliteSidecarIndex index) {
        return new WalkService(store, edgeDeriver, index::isTombstoned);
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

    @Bean
    public QuoteStatusService traceQuoteStatusService(TraceEventStore store, SqliteSidecarIndex index) {
        return new QuoteStatusService(store, index, Clock.systemUTC());
    }

    @Bean
    public IndexReconciler traceIndexReconciler(IndexedTraceEventStore store) {
        return new IndexReconciler(store);
    }
}
