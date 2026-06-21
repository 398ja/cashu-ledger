package xyz.tcheeric.cashu.ledger.trace.publisher.spring;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import xyz.tcheeric.cashu.ledger.trace.publisher.OverflowPolicy;

/**
 * Configuration for the embeddable traceability publisher starter (design US-5). A producer
 * service sets these to enable asynchronous, idempotent delivery of trace events to its private
 * relay set; the publisher is off unless {@code enabled} is true.
 */
@ConfigurationProperties(prefix = "cashu.trace.publisher")
public class TracePublisherProperties {

    /** Whether to wire the publisher stack. */
    private boolean enabled = false;

    /** Producer signing key (hex). Required when enabled. */
    private String privateKeyHex;

    /** Private, authenticated relay URLs to publish to. */
    private List<String> relays = new ArrayList<>();

    /** Per-relay publish timeout, seconds. */
    private long timeoutSeconds = 10;

    /** 256-bit redaction key (hex) for HASHED-mode events; omit to support only FULL/MINIMAL. */
    private String redactionKeyHex;

    /** Identifier recorded on HASHED events so readers know which key was used. */
    private String redactionKeyId;

    /** Behaviour when the durable outbox is at capacity. */
    private OverflowPolicy overflowPolicy = OverflowPolicy.BLOCK_AND_ALERT;

    /** JDBC URL for the durable outbox (use a file path for durability across restarts). */
    private String outboxJdbcUrl = "jdbc:sqlite::memory:";

    /** Wrap the publisher with an OpenTelemetry span (requires OpenTelemetry on the classpath). */
    private boolean tracing = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPrivateKeyHex() { return privateKeyHex; }
    public void setPrivateKeyHex(String privateKeyHex) { this.privateKeyHex = privateKeyHex; }
    public List<String> getRelays() { return relays; }
    public void setRelays(List<String> relays) { this.relays = relays; }
    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getRedactionKeyHex() { return redactionKeyHex; }
    public void setRedactionKeyHex(String redactionKeyHex) { this.redactionKeyHex = redactionKeyHex; }
    public String getRedactionKeyId() { return redactionKeyId; }
    public void setRedactionKeyId(String redactionKeyId) { this.redactionKeyId = redactionKeyId; }
    public OverflowPolicy getOverflowPolicy() { return overflowPolicy; }
    public void setOverflowPolicy(OverflowPolicy overflowPolicy) { this.overflowPolicy = overflowPolicy; }
    public String getOutboxJdbcUrl() { return outboxJdbcUrl; }
    public void setOutboxJdbcUrl(String outboxJdbcUrl) { this.outboxJdbcUrl = outboxJdbcUrl; }
    public boolean isTracing() { return tracing; }
    public void setTracing(boolean tracing) { this.tracing = tracing; }
}
