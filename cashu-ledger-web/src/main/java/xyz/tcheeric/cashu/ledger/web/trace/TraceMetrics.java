package xyz.tcheeric.cashu.ledger.web.trace;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.ToDoubleFunction;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.ledger.core.trace.IndexReconciler;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestMetrics;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestService;

/**
 * Publishes trace ledger gauges to Micrometer/Prometheus (design §8 observability, T073):
 * indexed-event and tombstone counts from the sidecar, and ingest reconciliation counters
 * (accepted/duplicates/rejected/conflicts) when the ingest path is enabled. Walk and
 * visualisation latencies are timed at the controller; this binder covers the storage and
 * ingest gauges scraped at {@code /actuator/prometheus}.
 */
@Component
public final class TraceMetrics implements MeterBinder {

    private final SqliteSidecarIndex index;
    private final ObjectProvider<TraceIngestService> ingestServiceProvider;
    private final IndexReconciler indexReconciler;

    public TraceMetrics(SqliteSidecarIndex index,
                        ObjectProvider<TraceIngestService> ingestServiceProvider,
                        IndexReconciler indexReconciler) {
        this.index = index;
        this.ingestServiceProvider = ingestServiceProvider;
        this.indexReconciler = indexReconciler;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("cashu_trace_indexed_events", index, i -> i.count())
                .description("Trace events currently indexed in the sidecar")
                .register(registry);
        Gauge.builder("cashu_trace_tombstones", index, i -> i.tombstoneCount())
                .description("Pruned trace events retained as tombstones")
                .register(registry);
        Gauge.builder("cashu_trace_index_pending_events", indexReconciler, r -> r.pendingEvents())
                .description("Events in the system of record not yet projected into the sidecar")
                .register(registry);
        registerIngestGauge(registry, "cashu_trace_ingest_accepted", TraceIngestMetrics::stored);
        registerIngestGauge(registry, "cashu_trace_ingest_duplicates", TraceIngestMetrics::duplicates);
        registerIngestGauge(registry, "cashu_trace_ingest_rejected", TraceIngestMetrics::rejected);
        registerIngestGauge(registry, "cashu_trace_ingest_conflicts", TraceIngestMetrics::conflicts);
    }

    private void registerIngestGauge(MeterRegistry registry, String name,
                                     ToDoubleFunction<TraceIngestMetrics> field) {
        Gauge.builder(name, this, self -> self.ingestValue(field))
                .description("Trace ingest counter (0 when ingest is disabled)")
                .register(registry);
    }

    private double ingestValue(ToDoubleFunction<TraceIngestMetrics> field) {
        TraceIngestService ingest = ingestServiceProvider.getIfAvailable();
        return ingest == null ? 0d : field.applyAsDouble(ingest.metrics());
    }
}
