package xyz.tcheeric.cashu.ledger.trace.publisher.spring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.InMemoryOutboxStore;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxRecord;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the meters the spec-048 alert rules match on.
 *
 * <p>{@code TracePublisherOutboxDepthWarn}, {@code ...Critical} and
 * {@code TracePublisherPublishErrorRateHigh} were written against
 * {@code gateway_trace_publisher_outbox_depth} and
 * {@code gateway_trace_publisher_publish_attempts_total}, and neither meter
 * existed. {@code OutboxDepthAlertTest} drives an in-test counter and asserts
 * the same expression <em>shape</em>, which checks the alert logic and nothing
 * about whether a scrape can produce the series — so the rules could never
 * fire while the suite stayed green.
 *
 * <p>These tests therefore assert against the registry, including the tags,
 * because a rule selecting {@code environment="prod"} matches nothing at all
 * if the series is untagged.
 */
class TracePublisherMetricsTest {

    private static final String DEPTH = "gateway_trace_publisher_outbox_depth";
    private static final String ATTEMPTS = "gateway_trace_publisher_publish_attempts_total";

    /** The depth gauge must track the store, which is what the threshold rules read. */
    @Test
    void outboxDepthGaugeTracksPendingCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxStore outbox = new InMemoryOutboxStore();
        new TracePublisherMetrics(registry, outbox, "staging", "gateway-customer");

        assertThat(registry.get(DEPTH).gauge().value())
                .as("an empty outbox is depth 0, not an absent series")
                .isZero();

        outbox.enqueue(record("op-1"));
        outbox.enqueue(record("op-2"));

        assertThat(registry.get(DEPTH).gauge().value())
                .as("the gauge must re-read the store, not cache its first value")
                .isEqualTo(2.0d);
    }

    /**
     * Every rule selects on {@code environment}, and the error-rate rule groups
     * by {@code service} and {@code producer}. An untagged series matches none
     * of them, so the tags are part of the contract.
     */
    @Test
    void metersCarryTheTagsTheRulesSelectOn() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new TracePublisherMetrics(registry, new InMemoryOutboxStore(), "prod", "gateway-customer");

        assertThat(registry.get(DEPTH).gauge().getId().getTag("environment")).isEqualTo("prod");
        assertThat(registry.get(DEPTH).gauge().getId().getTag("service")).isEqualTo("gateway-customer");

        var errors = registry.get(ATTEMPTS).tag("outcome", "error").counter();
        assertThat(errors.getId().getTag("environment")).isEqualTo("prod");
        assertThat(errors.getId().getTag("producer")).isEqualTo("gateway-customer");
    }

    /**
     * Both outcomes must exist before anything happens. The error-rate rule
     * divides by the total attempt rate, and an absent denominator makes the
     * whole expression evaluate to nothing rather than to a healthy zero.
     */
    @Test
    void bothOutcomeCountersAreRegisteredEagerly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new TracePublisherMetrics(registry, new InMemoryOutboxStore(), "staging", "svc");

        assertThat(registry.get(ATTEMPTS).tag("outcome", "success").counter().count()).isZero();
        assertThat(registry.get(ATTEMPTS).tag("outcome", "error").counter().count()).isZero();
    }

    /** Recorded outcomes must land on the matching counter. */
    @Test
    void recordsPublishOutcomesSeparately() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TracePublisherMetrics metrics =
                new TracePublisherMetrics(registry, new InMemoryOutboxStore(), "staging", "svc");

        metrics.recordPublishSuccess();
        metrics.recordPublishError();
        metrics.recordPublishError();

        assertThat(registry.get(ATTEMPTS).tag("outcome", "success").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(ATTEMPTS).tag("outcome", "error").counter().count()).isEqualTo(2.0d);
    }

    /** A store that throws must not take the scrape down with it. */
    @Test
    void depthGaugeSurvivesAnUnreadableStore() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // InMemoryOutboxStore is final, so the failure is injected through the
        // interface rather than by subclassing it.
        OutboxStore broken = new FailingPendingCountStore();
        new TracePublisherMetrics(registry, broken, "staging", "svc");

        assertThat(registry.get(DEPTH).gauge().value())
                .as("a failing store must not propagate out of a scrape")
                .isZero();
    }

    /** Delegates everything except pendingCount, which fails. */
    private static final class FailingPendingCountStore implements OutboxStore {
        private final OutboxStore delegate = new InMemoryOutboxStore();

        @Override
        public long pendingCount() {
            throw new IllegalStateException("store unavailable");
        }

        @Override
        public boolean enqueue(OutboxRecord record) {
            return delegate.enqueue(record);
        }

        @Override
        public java.util.List<OutboxRecord> claimBatch(int max, long nowEpochMs) {
            return delegate.claimBatch(max, nowEpochMs);
        }

        @Override
        public void markDelivered(String operationId) {
            delegate.markDelivered(operationId);
        }

        @Override
        public void recordFailure(String operationId, long nextAttemptAtEpochMs) {
            delegate.recordFailure(operationId, nextAttemptAtEpochMs);
        }

        @Override
        public java.util.Optional<OutboxRecord> find(String operationId) {
            return delegate.find(operationId);
        }

        @Override
        public java.util.List<OutboxRecord> stuck(int minAttempts, int limit) {
            return delegate.stuck(minAttempts, limit);
        }

        @Override
        public java.util.Optional<String> deleteOldestPending() {
            return delegate.deleteOldestPending();
        }
    }

    private OutboxRecord record(String operationId) {
        return OutboxRecord.pending(operationId, "evt-" + operationId, "{\"id\":\"evt-" + operationId + "\"}", 0L);
    }
}
