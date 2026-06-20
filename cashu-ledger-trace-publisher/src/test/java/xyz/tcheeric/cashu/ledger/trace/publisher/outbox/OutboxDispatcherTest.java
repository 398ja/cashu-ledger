package xyz.tcheeric.cashu.ledger.trace.publisher.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.publisher.RelayPublishResult;
import xyz.tcheeric.cashu.ledger.trace.publisher.RelayPublisher;

/**
 * Unit tests for {@link OutboxDispatcher}: successful delivery clears rows, and
 * failures are rescheduled with exponential backoff.
 */
class OutboxDispatcherTest {

    private static OutboxRecord row(String opId) {
        return OutboxRecord.pending(opId, "evt-" + opId, "{\"id\":\"evt-" + opId + "\"}", 0L);
    }

    /** A relay publisher whose verdict is configurable per call. */
    private static final class FakeRelayPublisher implements RelayPublisher {
        private final boolean deliver;
        final List<String> published = new ArrayList<>();

        FakeRelayPublisher(boolean deliver) {
            this.deliver = deliver;
        }

        @Override
        public RelayPublishResult publish(String eventJson, String eventId) {
            published.add(eventId);
            return deliver ? RelayPublishResult.delivered() : RelayPublishResult.failed("relay down");
        }
    }

    /** Tests that delivered rows are marked delivered and removed from pending. */
    @Test
    void shouldMarkDeliveredOnSuccess() {
        // Arrange
        OutboxStore store = new InMemoryOutboxStore();
        store.enqueue(row("op-1"));
        FakeRelayPublisher relay = new FakeRelayPublisher(true);
        OutboxDispatcher dispatcher = new OutboxDispatcher(store, relay, 64, 250, () -> 1_000L);

        // Act
        int delivered = dispatcher.drainOnce(1_000L);

        // Then
        assertThat(delivered).isEqualTo(1);
        assertThat(store.pendingCount()).isZero();
        assertThat(relay.published).containsExactly("evt-op-1");
    }

    /** Tests that a failed delivery reschedules the row with a backoff in the future. */
    @Test
    void shouldRescheduleWithBackoffOnFailure() {
        // Arrange
        OutboxStore store = new InMemoryOutboxStore();
        store.enqueue(row("op-1"));
        OutboxDispatcher dispatcher = new OutboxDispatcher(store, new FakeRelayPublisher(false),
                64, 250, () -> 1_000L);

        // Act
        int delivered = dispatcher.drainOnce(1_000L);

        // Then: still pending, attempts incremented, next attempt pushed out by initial backoff
        assertThat(delivered).isZero();
        OutboxRecord updated = store.find("op-1").orElseThrow();
        assertThat(updated.attempts()).isEqualTo(1);
        assertThat(updated.nextAttemptAtEpochMs()).isEqualTo(1_000L + OutboxDispatcher.INITIAL_BACKOFF_MS);
        // Not due yet at the same instant
        assertThat(store.claimBatch(10, 1_000L)).isEmpty();
    }

    /** Tests that backoff grows exponentially and is capped at the maximum. */
    @Test
    void shouldComputeCappedExponentialBackoff() {
        // Then
        assertThat(OutboxDispatcher.backoffMillis(0)).isEqualTo(OutboxDispatcher.INITIAL_BACKOFF_MS);
        assertThat(OutboxDispatcher.backoffMillis(1)).isEqualTo(2 * OutboxDispatcher.INITIAL_BACKOFF_MS);
        assertThat(OutboxDispatcher.backoffMillis(3)).isEqualTo(8 * OutboxDispatcher.INITIAL_BACKOFF_MS);
        assertThat(OutboxDispatcher.backoffMillis(100)).isEqualTo(OutboxDispatcher.MAX_BACKOFF_MS);
    }
}
