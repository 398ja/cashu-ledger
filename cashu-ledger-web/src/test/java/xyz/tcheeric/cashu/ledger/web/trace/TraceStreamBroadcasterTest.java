package xyz.tcheeric.cashu.ledger.web.trace;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import xyz.tcheeric.cashu.ledger.core.trace.TraceEventSummary;

/**
 * Unit tests for {@link TraceStreamBroadcaster} subscriber bookkeeping: registration
 * accounting and that publishing is safe with and without subscribers.
 */
class TraceStreamBroadcasterTest {

    private final TraceStreamBroadcaster broadcaster = new TraceStreamBroadcaster();

    /** Each subscribe registers exactly one emitter. */
    @Test
    void shouldRegisterEachSubscriber() {
        // Given: a fresh broadcaster
        assertThat(broadcaster.subscriberCount()).isZero();

        // When: two subscribers connect
        broadcaster.subscribe(1000);
        broadcaster.subscribe(1000);

        // Then: both are tracked
        assertThat(broadcaster.subscriberCount()).isEqualTo(2);
    }

    /** Publishing with no subscribers is a safe no-op. */
    @Test
    void shouldIgnorePublishWhenNoSubscribers() {
        // Given: no subscribers
        // When/Then: publishing does not throw
        assertThatCode(() -> broadcaster.publish(summary())).doesNotThrowAnyException();
    }

    /** Publishing to a healthy subscriber keeps it registered. */
    @Test
    void shouldRetainSubscriberAfterPublish() {
        // Given: one subscriber
        SseEmitter emitter = broadcaster.subscribe(1000);
        assertThat(emitter).isNotNull();

        // When: an event is published
        broadcaster.publish(summary());

        // Then: the subscriber remains registered
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
    }

    private static TraceEventSummary summary() {
        return new TraceEventSummary("e1", "op-1", "swap", "https://mint.imani.casa",
                Instant.EPOCH, "active", null);
    }
}
