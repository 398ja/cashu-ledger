package xyz.tcheeric.cashu.ledger.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hard limits that cap caller-supplied parameters on the read API (design §8.5 / NFR caps), so a
 * single request cannot ask the ledger to materialise an unbounded graph or page.
 */
@Data
@ConfigurationProperties(prefix = "trace.limits")
public class TraceLimitsProperties {

    /** Maximum nodes a walk or visualisation may return, regardless of the requested limit. */
    private int maxWalkNodes = 1000;

    /** Maximum page size for listing endpoints, regardless of the requested limit. */
    private int maxPageLimit = 1000;
}
