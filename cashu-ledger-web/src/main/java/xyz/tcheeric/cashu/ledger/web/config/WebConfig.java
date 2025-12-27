package xyz.tcheeric.cashu.ledger.web.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.ledger.core.relay.NostrRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerServiceImpl;

@Configuration
@EnableConfigurationProperties(WebLedgerProperties.class)
public class WebConfig {

    @Bean
    public VoucherLedgerService voucherLedgerService(WebLedgerProperties properties) {
        return new VoucherLedgerServiceImpl(
                new NostrRelayConnectionManager(),
                properties.getRelays(),
                properties.getTimeout(),
                properties.getTimeout(),
                properties.getCacheTtl()
        );
    }
}
