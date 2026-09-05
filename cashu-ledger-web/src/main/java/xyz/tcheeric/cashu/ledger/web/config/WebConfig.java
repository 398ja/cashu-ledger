package xyz.tcheeric.cashu.ledger.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.ledger.core.relay.CachingRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.relay.NostrRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.relay.RelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerServiceImpl;
import xyz.tcheeric.cashu.ledger.core.trace.IssuerAttestationConfig;
import xyz.tcheeric.cashu.ledger.core.storage.EventStore;
import xyz.tcheeric.cashu.ledger.core.storage.EventStoreConfig;
import xyz.tcheeric.cashu.ledger.core.storage.NostrDbEventStore;

import java.nio.file.Path;
import java.util.Optional;

@Configuration
@EnableConfigurationProperties(WebLedgerProperties.class)
public class WebConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ledger.web.storage.enabled", havingValue = "true")
    public EventStore eventStore(WebLedgerProperties properties) {
        WebLedgerProperties.StorageProperties storage = properties.getStorage();
        EventStoreConfig config = new EventStoreConfig(
                Path.of(storage.getPath()),
                storage.getMaxSizeBytes(),
                storage.getEventTtl(),
                false
        );
        LOGGER.info("event_store_configured path={} max_size_bytes={} event_ttl={}",
                config.databasePath(), config.maxSizeBytes(), config.eventTtl());
        return new NostrDbEventStore(config);
    }

    @Bean
    public RelayConnectionManager relayConnectionManager(
            WebLedgerProperties properties,
            Optional<EventStore> eventStore) {

        NostrRelayConnectionManager baseManager = new NostrRelayConnectionManager();

        if (eventStore.isPresent() && properties.getStorage().isEnabled()) {
            LOGGER.info("relay_manager_configured type=caching");
            return new CachingRelayConnectionManager(baseManager, eventStore.get(), true);
        }

        LOGGER.info("relay_manager_configured type=direct");
        return baseManager;
    }

    @Bean
    public VoucherLedgerService voucherLedgerService(
            WebLedgerProperties properties,
            RelayConnectionManager relayConnectionManager) {
        // Without this registry no issuer can be attested, so verify() reports every voucher's
        // signature as untrusted and /verify answers signatureValid=false for everything. The
        // default is deliberately fail-closed, but a default nobody can change is just broken,
        // so warn loudly rather than let it look like a verification result.
        IssuerAttestationConfig issuerAttestation =
                new IssuerAttestationConfig(properties.getIssuerKeys());
        if (issuerAttestation.isEmpty()) {
            LOGGER.warn("No ledger.web.issuer-keys configured: voucher verification will report "
                    + "signatureValid=false for every voucher, because no issuer key is trusted. "
                    + "Configure ledger.web.issuer-keys.<issuerId>=<hex pubkey> to enable it.");
        }
        return new VoucherLedgerServiceImpl(
                relayConnectionManager,
                properties.getRelays(),
                properties.getTimeout(),
                properties.getTimeout(),
                properties.getCacheTtl(),
                issuerAttestation
        );
    }
}
