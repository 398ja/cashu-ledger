package xyz.tcheeric.cashu.ledger.web.trace;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import xyz.tcheeric.cashu.ledger.core.trace.TraceEventSummary;

/**
 * Fan-out hub for live trace-ingestion notifications (design §5.5 — SSE stream, mirroring
 * the voucher {@code WatchController}). Subscribers register an {@link SseEmitter}; the
 * ingestion path calls {@link #publish(TraceEventSummary)} for each newly ingested event and
 * every open emitter receives a secret-free summary. Dead emitters are pruned on send failure
 * and on completion/timeout.
 */
@Component
public final class TraceStreamBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceStreamBroadcaster.class);
    private static final String EVENT_NAME = "trace";

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Registers a new subscriber and prunes it on completion, timeout, or error. */
    public SseEmitter subscribe(long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(error -> emitters.remove(emitter));
        return emitter;
    }

    /** Broadcasts a secret-free event summary to every open subscriber. */
    public void publish(TraceEventSummary summary) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(EVENT_NAME).data(summary));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
                LOGGER.debug("trace_stream_subscriber_dropped reason={}", e.getMessage());
            }
        }
    }

    /** The number of currently registered subscribers. */
    public int subscriberCount() {
        return emitters.size();
    }
}
