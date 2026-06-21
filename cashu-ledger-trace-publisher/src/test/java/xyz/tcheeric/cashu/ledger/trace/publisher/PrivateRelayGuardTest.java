package xyz.tcheeric.cashu.ledger.trace.publisher;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PrivateRelayGuard}: a publisher must refuse to start unless
 * every configured relay is private (design §7.2 / FR-030).
 */
class PrivateRelayGuardTest {

    /** Tests that an all-private relay set passes validation. */
    @Test
    void shouldAcceptAllPrivateRelays() {
        // Arrange
        List<RelayConfig> relays = List.of(
                RelayConfig.privateRelay("wss://relay.imani.casa"),
                new RelayConfig("wss://backup.imani.casa", true, "fallback"));

        // Act / Then
        assertThatCode(() -> PrivateRelayGuard.requireAllPrivate(relays)).doesNotThrowAnyException();
    }

    /** Tests that any public relay in the set is a fatal startup error. */
    @Test
    void shouldRejectNonPrivateRelay() {
        // Arrange
        List<RelayConfig> relays = List.of(
                RelayConfig.privateRelay("wss://relay.imani.casa"),
                new RelayConfig("wss://public.example", false, "fallback"));

        // Act / Then
        assertThatThrownBy(() -> PrivateRelayGuard.requireAllPrivate(relays))
                .isInstanceOf(TraceabilityPublishException.class)
                .hasMessageContaining("public.example");
    }

    /** Tests that an empty relay set is rejected (nowhere to publish). */
    @Test
    void shouldRejectEmptyRelaySet() {
        // Act / Then
        assertThatThrownBy(() -> PrivateRelayGuard.requireAllPrivate(List.of()))
                .isInstanceOf(TraceabilityPublishException.class);
    }
}
