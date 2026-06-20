package xyz.tcheeric.cashu.ledger.trace.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NostrRelayPublisher}: recognising an OK acknowledgement for the right
 * event and refusing to construct against a non-private relay.
 */
class NostrRelayPublisherTest {

    private static final String EVENT_ID = "abc123";

    /** An OK:true frame for the matching event id counts as acknowledged. */
    @Test
    void shouldRecogniseMatchingOkAcknowledgement() {
        List<String> responses = List.of(
                "[\"OK\",\"abc123\",true,\"\"]",
                "[\"NOTICE\",\"hello\"]");
        assertThat(NostrRelayPublisher.acknowledged(responses, EVENT_ID)).isTrue();
    }

    /** OK:false, a different id, or no OK frame are all not acknowledged. */
    @Test
    void shouldRejectNonAcknowledgements() {
        assertThat(NostrRelayPublisher.acknowledged(
                List.of("[\"OK\",\"abc123\",false,\"blocked\"]"), EVENT_ID)).isFalse();
        assertThat(NostrRelayPublisher.acknowledged(
                List.of("[\"OK\",\"other\",true,\"\"]"), EVENT_ID)).isFalse();
        assertThat(NostrRelayPublisher.acknowledged(
                List.of("[\"NOTICE\",\"nope\"]", "garbage"), EVENT_ID)).isFalse();
    }

    /** Construction refuses a non-private relay (private-relay guard). */
    @Test
    void shouldRefuseNonPrivateRelay() {
        List<RelayConfig> publicRelay = List.of(new RelayConfig("wss://public.example", false, "primary"));
        assertThatThrownBy(() -> new NostrRelayPublisher(publicRelay, 5))
                .isInstanceOf(TraceabilityPublishException.class)
                .hasMessageContaining("private");
    }
}
