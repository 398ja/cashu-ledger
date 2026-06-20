package xyz.tcheeric.cashu.ledger.web.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retention configuration (design §5.11, FR-025). Off by default; when enabled the retention
 * engine runs on a schedule, pruning by age and (optionally) terminal sub-DAGs ahead of the age
 * threshold.
 */
@Data
@ConfigurationProperties(prefix = "trace.retention")
public class TraceRetentionProperties {

    /** Whether to run scheduled pruning. */
    private boolean enabled = false;

    /** Events older than this are pruned. */
    private Duration ageWindow = Duration.ofDays(90);

    /** Terminal events older than this are pruned ahead of the age threshold. */
    private Duration terminalWindow = Duration.ofDays(7);

    /** Whether terminal sub-DAG pruning is active (else age-only). */
    private boolean terminalSubdagEnabled = true;

    /** Maximum events pruned per pass. */
    private int batchLimit = 1000;

    /** Delay between retention passes. */
    private Duration sweepDelay = Duration.ofHours(1);
}
