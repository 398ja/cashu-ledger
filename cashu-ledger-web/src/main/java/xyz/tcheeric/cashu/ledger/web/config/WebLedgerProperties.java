package xyz.tcheeric.cashu.ledger.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "ledger.web")
public class WebLedgerProperties {
    private List<String> relays = List.of("wss://relay.imani.casa");
    private Duration timeout = Duration.ofSeconds(30);
    private Duration cacheTtl = Duration.ofSeconds(30);

    public List<String> getRelays() {
        return relays;
    }

    public void setRelays(List<String> relays) {
        this.relays = relays;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }
}
