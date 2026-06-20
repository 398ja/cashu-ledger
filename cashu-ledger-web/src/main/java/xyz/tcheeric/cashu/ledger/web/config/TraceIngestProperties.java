package xyz.tcheeric.cashu.ledger.web.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the ledger ingest path (design §6.1). When {@code enabled}, the web
 * application subscribes to the configured relays, validates and stores kind-9079 trace events,
 * watches kind-30078 voucher state, and feeds the activity cache and live stream. Disabled by
 * default so a read-only deployment (or a test context) does not open relay connections.
 */
@Data
@ConfigurationProperties(prefix = "trace.ingest")
public class TraceIngestProperties {

    /** Whether to run the ingest path (relay subscriptions + storage). */
    private boolean enabled = false;

    /** Whether to accept events older than the retention window (admin backfill). */
    private boolean allowHistorical = false;

    /** Authorised producers per mint; events signed by other keys are rejected. */
    private List<Producer> producers = new ArrayList<>();

    /** A mint and the producer pubkeys authorised to publish trace events for it. */
    @Data
    public static class Producer {
        private String mintUrl;
        private List<String> pubkeys = new ArrayList<>();
    }
}
