package xyz.tcheeric.cashu.ledger.web.controller;

import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import xyz.tcheeric.cashu.ledger.web.trace.TraceStreamBroadcaster;

/**
 * Server-sent-events stream of live trace-ingestion notifications (design §5.5). Lives under
 * {@code /api/v1/trace} so the NIP-98 authentication filter gates it like the other read
 * endpoints; each open connection receives secret-free event summaries as they are ingested.
 */
@RestController
@RequestMapping("/api/v1/trace")
public class TraceStreamController {

    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(5).toMillis();

    private final TraceStreamBroadcaster broadcaster;

    public TraceStreamController(TraceStreamBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe(STREAM_TIMEOUT_MILLIS);
    }
}
