package xyz.tcheeric.cashu.ledger.trace.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Unit tests for {@link TracingTraceabilityPublisher}: it records a publish span with the
 * operation attributes, delegates the call, and marks the span failed when publishing throws.
 */
class TracingTraceabilityPublisherTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    private final Tracer tracer = otel.getOpenTelemetry().getTracer("test");

    /** A successful publish records one trace.publish span carrying the operation id. */
    @Test
    void shouldRecordSpanAndDelegate() {
        // Given: a recording delegate wrapped with tracing
        RecordingPublisher delegate = new RecordingPublisher();
        TracingTraceabilityPublisher publisher = new TracingTraceabilityPublisher(delegate, tracer);

        // When: publishing an event
        publisher.publish(event());

        // Then: the delegate was called and a span with the operation id was recorded
        assertThat(delegate.published).isEqualTo(1);
        assertThat(otel.getSpans()).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("trace.publish");
            assertThat(span.getAttributes().get(AttributeKey.stringKey("cashu.trace.operation_id")))
                    .isEqualTo("op-1");
        });
    }

    /** A failing publish ends the span with ERROR status and rethrows. */
    @Test
    void shouldMarkSpanErrorWhenPublishFails() {
        // Given: a delegate that throws
        TraceabilityPublisher delegate = new TraceabilityPublisher() {
            @Override
            public void publish(TransactionEvent e) {
                throw new TraceabilityPublishException("BOOM", "delivery failed");
            }

            @Override
            public PublisherHealth health() {
                return new PublisherHealth(0, 0, OverflowPolicy.BLOCK_AND_ALERT);
            }
        };
        TracingTraceabilityPublisher publisher = new TracingTraceabilityPublisher(delegate, tracer);

        // When/Then: the exception propagates and the span is ERROR
        assertThatThrownBy(() -> publisher.publish(event()))
                .isInstanceOf(TraceabilityPublishException.class);
        assertThat(otel.getSpans()).singleElement()
                .satisfies(span -> assertThat(span.getStatus().getStatusCode())
                        .isEqualTo(StatusData.error().getStatusCode()));
    }

    private static TransactionEvent event() {
        return new TransactionEvent(
                Optional.empty(), "op-1", OperationKind.SWAP, "https://mint.imani.casa", "sat",
                Instant.ofEpochSecond(1_700_000_000L), Instant.ofEpochSecond(1_700_000_000L), "pk",
                Optional.empty(), List.of(), List.of(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                PrivacyMode.MINIMAL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.empty(), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(1_700_000_000L)));
    }

    private static final class RecordingPublisher implements TraceabilityPublisher {
        private int published;

        @Override
        public void publish(TransactionEvent event) {
            published++;
        }

        @Override
        public PublisherHealth health() {
            return new PublisherHealth(0, 0, OverflowPolicy.BLOCK_AND_ALERT);
        }
    }
}
