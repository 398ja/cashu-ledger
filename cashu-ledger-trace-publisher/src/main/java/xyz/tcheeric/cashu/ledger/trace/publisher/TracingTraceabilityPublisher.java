package xyz.tcheeric.cashu.ledger.trace.publisher;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Decorates a {@link TraceabilityPublisher} with an OpenTelemetry span around each publish
 * (design §6.1 — link the user operation to its trace publish). The {@code trace.publish} span
 * parents to the current context, so it nests under the user-operation span the producer is
 * already recording, and carries the operation id, kind, and mint for correlation. This module's
 * only dependency on OpenTelemetry; embedders that do not use OTel keep the plain publisher.
 */
public final class TracingTraceabilityPublisher implements TraceabilityPublisher {

    private static final String INSTRUMENTATION = "cashu-ledger-trace-publisher";
    private static final String SPAN_NAME = "trace.publish";

    private final TraceabilityPublisher delegate;
    private final Tracer tracer;

    public TracingTraceabilityPublisher(TraceabilityPublisher delegate, Tracer tracer) {
        this.delegate = delegate;
        this.tracer = tracer;
    }

    /** Wraps {@code delegate} using the globally-registered OpenTelemetry instance. */
    public static TracingTraceabilityPublisher wrap(TraceabilityPublisher delegate) {
        return new TracingTraceabilityPublisher(delegate, GlobalOpenTelemetry.getTracer(INSTRUMENTATION));
    }

    @Override
    public void publish(TransactionEvent event) {
        Span span = tracer.spanBuilder(SPAN_NAME)
                .setAttribute("cashu.trace.operation_id", event.operationId())
                .setAttribute("cashu.trace.op", event.kind().wireValue())
                .setAttribute("cashu.trace.mint_url", event.mintUrl())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            delegate.publish(event);
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public PublisherHealth health() {
        return delegate.health();
    }
}
