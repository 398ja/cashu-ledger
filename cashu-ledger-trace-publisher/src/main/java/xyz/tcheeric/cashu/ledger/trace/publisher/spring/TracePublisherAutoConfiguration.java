package xyz.tcheeric.cashu.ledger.trace.publisher.spring;

import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRefRedactor;
import xyz.tcheeric.cashu.ledger.trace.publisher.DefaultTraceabilityPublisher;
import xyz.tcheeric.cashu.ledger.trace.publisher.NostrRelayPublisher;
import xyz.tcheeric.cashu.ledger.trace.publisher.RelayConfig;
import xyz.tcheeric.cashu.ledger.trace.publisher.RelayPublisher;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceEventSigner;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceabilityPublisher;
import xyz.tcheeric.cashu.ledger.trace.publisher.TracingTraceabilityPublisher;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxDispatcher;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxStore;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.SqliteOutboxStore;

/**
 * Spring Boot starter that wires the traceability publisher stack from
 * {@link TracePublisherProperties} (design US-5 / T025). Active only when
 * {@code cashu.trace.publisher.enabled=true}. Provides the durable outbox, the signer, the
 * redactor, the nostr relay transport, the publisher (optionally OpenTelemetry-wrapped), and a
 * running outbox dispatcher. All beans are {@code @ConditionalOnMissingBean} so an application
 * can override any of them.
 */
@AutoConfiguration
@EnableConfigurationProperties(TracePublisherProperties.class)
@ConditionalOnProperty(prefix = "cashu.trace.publisher", name = "enabled", havingValue = "true")
public class TracePublisherAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public OutboxStore traceOutboxStore(TracePublisherProperties properties) {
        return new SqliteOutboxStore(properties.getOutboxJdbcUrl());
    }

    /**
     * Registers the spec-048 publisher meters when Micrometer is on the
     * classpath and a registry exists.
     *
     * <p>Nested so the {@code @ConditionalOnClass} guard is evaluated against
     * the nested class rather than the enclosing auto-configuration: a
     * consumer without Micrometer (or without actuator) keeps the whole
     * publisher stack, minus the meters.
     */
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
            io.micrometer.core.instrument.MeterRegistry.class)
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TracePublisherMetrics tracePublisherMetrics(
                org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries,
                OutboxStore outbox,
                org.springframework.core.env.Environment env) {

            io.micrometer.core.instrument.MeterRegistry registry = registries.getIfAvailable();
            if (registry == null) {
                return null;
            }
            return new TracePublisherMetrics(registry, outbox,
                    env.getProperty("ENVIRONMENT", "local"),
                    env.getProperty("spring.application.name", "unknown"));
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceEventSigner traceEventSigner(TracePublisherProperties properties) {
        return new TraceEventSigner(properties.getPrivateKeyHex());
    }

    @Bean
    @ConditionalOnMissingBean
    public ProofRefRedactor traceProofRefRedactor(TracePublisherProperties properties) {
        String keyHex = properties.getRedactionKeyHex();
        return keyHex == null || keyHex.isBlank()
                ? ProofRefRedactor.withoutKey()
                : new ProofRefRedactor(HexFormat.of().parseHex(keyHex));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RelayPublisher traceRelayPublisher(TracePublisherProperties properties) {
        List<RelayConfig> relays = properties.getRelays().stream()
                .map(RelayConfig::privateRelay)
                .toList();
        return new NostrRelayPublisher(relays, properties.getTimeoutSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceabilityPublisher traceabilityPublisher(OutboxStore outbox, TraceEventSigner signer,
                                                       ProofRefRedactor redactor,
                                                       TracePublisherProperties properties) {
        TraceabilityPublisher base = new DefaultTraceabilityPublisher(
                outbox, signer, redactor, properties.getOverflowPolicy(),
                DefaultTraceabilityPublisher.DEFAULT_CAPACITY,
                DefaultTraceabilityPublisher.DEFAULT_BLOCK_MILLIS,
                properties.getRedactionKeyId(), System::currentTimeMillis);
        return properties.isTracing() ? TracingTraceabilityPublisher.wrap(base) : base;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public OutboxDispatcher traceOutboxDispatcher(
            OutboxStore outbox,
            RelayPublisher relayPublisher,
            org.springframework.beans.factory.ObjectProvider<TracePublisherMetrics> metrics) {
        OutboxDispatcher dispatcher = OutboxDispatcher.create(outbox, relayPublisher);
        // Optional: absent when Micrometer is not on the classpath, in which
        // case the dispatcher keeps its no-op listener.
        TracePublisherMetrics publisherMetrics = metrics.getIfAvailable();
        if (publisherMetrics != null) {
            dispatcher.setOutcomeListener(new OutboxDispatcher.PublishOutcomeListener() {
                @Override
                public void onSuccess() {
                    publisherMetrics.recordPublishSuccess();
                }

                @Override
                public void onFailure() {
                    publisherMetrics.recordPublishError();
                }
            });
        }
        dispatcher.start();
        return dispatcher;
    }
}
