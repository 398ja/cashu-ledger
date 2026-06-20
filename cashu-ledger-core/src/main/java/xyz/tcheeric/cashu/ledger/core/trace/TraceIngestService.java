package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Orchestrates ingest of a raw kind-9079 event: parse → conflict/dedup check →
 * validate → store (design §4.5 / §5.9). Idempotent and safe to call repeatedly
 * for the same event (e.g. from multiple relays). Maintains observability counters.
 */
public final class TraceIngestService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceIngestService.class);

    private final TraceEventMapper mapper;
    private final TraceIngestValidator validator;
    private final TraceEventStore store;
    private final boolean allowHistorical;
    private final TraceIngestListener listener;
    private final TraceIngestMetricsRecorder metricsRecorder;

    private final AtomicLong stored = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();

    public TraceIngestService(TraceEventMapper mapper, TraceIngestValidator validator,
                              TraceEventStore store, boolean allowHistorical) {
        this(mapper, validator, store, allowHistorical, TraceIngestListener.NONE,
                TraceIngestMetricsRecorder.NOOP);
    }

    public TraceIngestService(TraceEventMapper mapper, TraceIngestValidator validator,
                              TraceEventStore store, boolean allowHistorical,
                              TraceIngestListener listener) {
        this(mapper, validator, store, allowHistorical, listener, TraceIngestMetricsRecorder.NOOP);
    }

    public TraceIngestService(TraceEventMapper mapper, TraceIngestValidator validator,
                              TraceEventStore store, boolean allowHistorical,
                              TraceIngestListener listener, TraceIngestMetricsRecorder metricsRecorder) {
        this.mapper = mapper;
        this.validator = validator;
        this.store = store;
        this.allowHistorical = allowHistorical;
        this.listener = listener;
        this.metricsRecorder = metricsRecorder;
    }

    /** Ingests one raw signed event JSON received from {@code relayUrl}. */
    public IngestOutcome ingest(String rawJson, String relayUrl) {
        ParsedTraceEvent parsed;
        try {
            parsed = mapper.parse(rawJson, relayUrl);
        } catch (TraceParseException e) {
            rejected.incrementAndGet();
            metricsRecorder.recordRejected("MALFORMED");
            LOGGER.warn("trace_event_rejected reason=malformed relay={} error={}", relayUrl, e.getMessage());
            return IngestOutcome.rejected(new IngestRejection("MALFORMED", e.getMessage()));
        }

        TransactionEvent event = parsed.event();
        String eventId = event.eventId().orElseThrow();

        Optional<IngestOutcome> conflict = detectConflict(event, eventId);
        if (conflict.isPresent()) {
            return conflict.get();
        }

        if (store.findByEventId(eventId).isPresent()) {
            duplicates.incrementAndGet();
            metricsRecorder.recordDuplicate();
            return IngestOutcome.duplicate(eventId);
        }

        Optional<IngestRejection> rejection = validator.validate(parsed, allowHistorical);
        if (rejection.isPresent()) {
            rejected.incrementAndGet();
            metricsRecorder.recordRejected(rejection.get().code());
            LOGGER.warn("trace_event_rejected event_id={} op={} code={} reason={}",
                    eventId, event.kind().wireValue(), rejection.get().code(), rejection.get().message());
            return IngestOutcome.rejected(rejection.get());
        }

        StoredEvent toStore = new StoredEvent(event, Optional.of(parsed.rawJson()));
        boolean isNew = store.store(toStore);
        if (!isNew) {
            duplicates.incrementAndGet();
            metricsRecorder.recordDuplicate();
            return IngestOutcome.duplicate(eventId);
        }
        stored.incrementAndGet();
        long lagMillis = Math.max(0, System.currentTimeMillis() - event.createdAt().toEpochMilli());
        metricsRecorder.recordStored(event.kind(), lagMillis);
        LOGGER.info("trace_event_stored event_id={} op={} mint_url={} ingest_lag_ms={}",
                eventId, event.kind().wireValue(), event.mintUrl(), lagMillis);
        notifyListener(toStore);
        return IngestOutcome.stored(eventId);
    }

    private Optional<IngestOutcome> detectConflict(TransactionEvent event, String eventId) {
        Optional<StoredEvent> existing = store.findByOperationId(event.operationId());
        if (existing.isPresent() && !existing.get().event().eventId().orElse("").equals(eventId)) {
            conflicts.incrementAndGet();
            metricsRecorder.recordConflict();
            LOGGER.warn("trace_event_conflict operation_id={} existing_event_id={} new_event_id={}",
                    event.operationId(), existing.get().event().eventId().orElse("?"), eventId);
            return Optional.of(IngestOutcome.conflict(eventId, new IngestRejection(
                    "OPERATION_CONFLICT",
                    "operation " + event.operationId() + " already stored with different content")));
        }
        return Optional.empty();
    }

    private void notifyListener(StoredEvent event) {
        try {
            listener.onStored(event);
        } catch (RuntimeException e) {
            LOGGER.warn("trace_ingest_listener_failed event_id={} error={}",
                    event.event().eventId().orElse("?"), e.getMessage());
        }
    }

    public TraceIngestMetrics metrics() {
        return new TraceIngestMetrics(stored.get(), duplicates.get(), rejected.get(), conflicts.get());
    }
}
