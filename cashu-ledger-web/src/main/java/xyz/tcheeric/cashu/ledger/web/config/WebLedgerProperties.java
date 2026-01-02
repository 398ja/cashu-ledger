package xyz.tcheeric.cashu.ledger.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "ledger.web")
public class WebLedgerProperties {
    private List<String> relays = List.of("wss://relay.imani.casa");
    private Duration timeout = Duration.ofSeconds(30);
    private Duration cacheTtl = Duration.ofSeconds(30);
    private StorageProperties storage = new StorageProperties();

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

    public StorageProperties getStorage() {
        return storage;
    }

    public void setStorage(StorageProperties storage) {
        this.storage = storage;
    }

    /**
     * Configuration properties for the local event store.
     */
    public static class StorageProperties {
        private boolean enabled = false;
        private String path;
        private long maxSizeBytes = 512L * 1024 * 1024; // 512MB
        private Duration eventTtl = Duration.ofDays(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPath() {
            if (path == null || path.isBlank()) {
                return Path.of(System.getProperty("user.home"), ".cashu-ledger", "ndb").toString();
            }
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public long getMaxSizeBytes() {
            return maxSizeBytes;
        }

        public void setMaxSizeBytes(long maxSizeBytes) {
            this.maxSizeBytes = maxSizeBytes;
        }

        public Duration getEventTtl() {
            return eventTtl;
        }

        public void setEventTtl(Duration eventTtl) {
            this.eventTtl = eventTtl;
        }
    }
}
