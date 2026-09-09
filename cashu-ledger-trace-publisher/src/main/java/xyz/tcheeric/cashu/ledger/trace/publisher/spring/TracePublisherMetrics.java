package xyz.tcheeric.cashu.ledger.trace.publisher.spring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxStore;

/**
 * The spec-048 publisher meters, in the shape the imani-deploy alert rules
 * match on.
 *
 * <p>Three rules — {@code TracePublisherOutboxDepthWarn},
 * {@code ...Critical} and {@code TracePublisherPublishErrorRateHigh} — were
 * written against {@code gateway_trace_publisher_outbox_depth} and
 * {@code gateway_trace_publisher_publish_attempts_total}, but nothing ever
 * registered either meter. {@code OutboxDepthAlertTest} drives an in-test
 * counter and asserts the same expression <em>shape</em> against scaled-down
 * thresholds, which genuinely checks the alert logic and says nothing about
 * whether a scrape can produce the series. The rules could therefore never
 * fire, which reads as coverage while providing none.
 *
 * <p>Names carry the {@code gateway_} prefix because that is what the deployed
 * rules select on, even though this module is not itself a gateway.
 */
final class TracePublisherMetrics {

    private static final String OUTBOX_DEPTH = "gateway_trace_publisher_outbox_depth";
    private static final String PUBLISH_ATTEMPTS = "gateway_trace_publisher_publish_attempts_total";

    /** Matches {@code outcome="error"} in the error-rate rule's numerator. */
    static final String OUTCOME_ERROR = "error";

    /** The denominator counts every attempt, so successes need a series too. */
    static final String OUTCOME_SUCCESS = "success";

    private final Counter errorAttempts;
    private final Counter successAttempts;

    /**
     * @param registry    the registry to publish to
     * @param outbox      polled for {@link OutboxStore#pendingCount()}
     * @param environment value of the {@code environment} tag, which every rule
     *                    selects on — an untagged series matches none of them
     * @param service     value of the {@code service} tag used to group the
     *                    error-rate rule
     */
    TracePublisherMetrics(MeterRegistry registry, OutboxStore outbox,
                          String environment, String service) {

        Gauge.builder(OUTBOX_DEPTH, outbox, TracePublisherMetrics::pendingOrZero)
                .description("Trace events awaiting delivery in the durable outbox")
                .tag("environment", environment)
                .tag("service", service)
                .strongReference(true)
                .register(registry);

        // Both outcomes are registered eagerly. A counter that first appears on
        // failure is absent until something breaks, and an absent denominator
        // makes the error-rate expression evaluate to nothing rather than to a
        // healthy zero.
        this.errorAttempts = attempts(registry, environment, service, OUTCOME_ERROR);
        this.successAttempts = attempts(registry, environment, service, OUTCOME_SUCCESS);
    }

    private static Counter attempts(MeterRegistry registry, String environment,
                                    String service, String outcome) {
        return Counter.builder(PUBLISH_ATTEMPTS)
                .description("Relay publish attempts, by outcome")
                .tag("environment", environment)
                .tag("service", service)
                .tag("producer", service)
                .tag("outcome", outcome)
                .register(registry);
    }

    /**
     * A store that cannot be read must not take the process down, and must not
     * report a falsely healthy depth either. Zero is the honest default here
     * because the depth alerts fire on a threshold being exceeded, so zero
     * cannot mask a breach — it only delays detection until the next poll.
     */
    private static double pendingOrZero(OutboxStore outbox) {
        try {
            return outbox.pendingCount();
        } catch (RuntimeException e) {
            return 0d;
        }
    }

    void recordPublishSuccess() {
        successAttempts.increment();
    }

    void recordPublishError() {
        errorAttempts.increment();
    }
}
