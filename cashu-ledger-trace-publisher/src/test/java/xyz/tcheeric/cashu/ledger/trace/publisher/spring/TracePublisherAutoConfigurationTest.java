package xyz.tcheeric.cashu.ledger.trace.publisher.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceabilityPublisher;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxDispatcher;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxStore;

/**
 * Tests the publisher starter: it wires the full stack when enabled with a valid configuration
 * and stays inert otherwise.
 */
class TracePublisherAutoConfigurationTest {

    private static final String PRIV =
            "0000000000000000000000000000000000000000000000000000000000000007";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracePublisherAutoConfiguration.class));

    /** With enabled=true and a private relay + key, the publisher stack is wired. */
    @Test
    void shouldWirePublisherStackWhenEnabled() {
        runner.withPropertyValues(
                        "cashu.trace.publisher.enabled=true",
                        "cashu.trace.publisher.private-key-hex=" + PRIV,
                        "cashu.trace.publisher.relays[0]=wss://relay.imani.casa",
                        "cashu.trace.publisher.outbox-jdbc-url=jdbc:sqlite::memory:")
                .run(context -> {
                    assertThat(context).hasSingleBean(TraceabilityPublisher.class);
                    assertThat(context).hasSingleBean(OutboxStore.class);
                    assertThat(context).hasSingleBean(OutboxDispatcher.class);
                });
    }

    /** Without the enabled flag, no publisher beans are created. */
    @Test
    void shouldStayInertWhenDisabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(TraceabilityPublisher.class);
            assertThat(context).doesNotHaveBean(OutboxDispatcher.class);
        });
    }
}
