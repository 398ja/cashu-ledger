package xyz.tcheeric.cashu.ledger.web.trace;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestMetricsRecorder;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;

/**
 * Micrometer-backed {@link TraceIngestMetricsRecorder} (T037): events ingested tagged by kind,
 * rejections tagged by reason, duplicate/conflict counters, and an ingest-to-visible lag timer —
 * scraped at {@code /actuator/prometheus}.
 */
public final class MicrometerTraceIngestMetrics implements TraceIngestMetricsRecorder {

    private final MeterRegistry registry;
    private final Timer ingestLag;
    private final Counter duplicates;
    private final Counter conflicts;

    public MicrometerTraceIngestMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ingestLag = Timer.builder("cashu_trace_ingest_lag")
                .description("Time from event created_at to ingest").register(registry);
        this.duplicates = Counter.builder("cashu_trace_ingest_duplicates_total")
                .description("Duplicate trace events received").register(registry);
        this.conflicts = Counter.builder("cashu_trace_ingest_conflicts_total")
                .description("Operation-id conflicts detected").register(registry);
    }

    @Override
    public void recordStored(OperationKind kind, long lagMillis) {
        registry.counter("cashu_trace_ingested_total", "op", kind.wireValue()).increment();
        ingestLag.record(lagMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordRejected(String reasonCode) {
        registry.counter("cashu_trace_rejected_total", "reason", reasonCode).increment();
    }

    @Override
    public void recordDuplicate() {
        duplicates.increment();
    }

    @Override
    public void recordConflict() {
        conflicts.increment();
    }
}
