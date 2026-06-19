package xyz.tcheeric.cashu.ledger.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.cashu.ledger.core.trace.EventPage;
import xyz.tcheeric.cashu.ledger.core.trace.ProofHistory;
import xyz.tcheeric.cashu.ledger.core.trace.TraceQueryService;
import xyz.tcheeric.cashu.ledger.trace.core.EventActivity;
import xyz.tcheeric.cashu.ledger.trace.core.IndexStatus;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventQuery;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.web.security.TracePrincipal;
import xyz.tcheeric.cashu.ledger.web.trace.EventPageView;
import xyz.tcheeric.cashu.ledger.web.trace.EventView;
import xyz.tcheeric.cashu.ledger.web.trace.TraceResponseMapper;

/**
 * Read API for transaction traceability (design §5.5). All endpoints are gated by
 * {@link xyz.tcheeric.cashu.ledger.web.security.Nip98AuthenticationFilter}; the
 * {@link TracePrincipal} request attribute determines the response shape. Responses
 * carry {@code Cache-Control: no-store} so secret-bearing payloads are not cached.
 */
@RestController
@RequestMapping("/api/v1/trace")
public class TraceController {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("trace.access");

    private final TraceQueryService queryService;
    private final TraceEventStore store;
    private final TraceResponseMapper mapper;

    public TraceController(TraceQueryService queryService, TraceEventStore store,
                          TraceResponseMapper mapper) {
        this.queryService = queryService;
        this.store = store;
        this.mapper = mapper;
    }

    @GetMapping("/events")
    public ResponseEntity<EventPageView> listEvents(
            HttpServletRequest request,
            @RequestParam(name = "mintUrl", required = false) String mintUrl,
            @RequestParam(name = "producerPubkey", required = false) String producerPubkey,
            @RequestParam(name = "initiatorPubkey", required = false) String initiatorPubkey,
            @RequestParam(name = "issuerId", required = false) String issuerId,
            @RequestParam(name = "issuerPubkey", required = false) String issuerPubkey,
            @RequestParam(name = "voucherRef", required = false) String voucherRef,
            @RequestParam(name = "quoteId", required = false) String quoteId,
            @RequestParam(name = "transferId", required = false) String transferId,
            @RequestParam(name = "op", required = false) String op,
            @RequestParam(name = "activity", required = false) String activity,
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "until", required = false) String until,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {

        TracePrincipal principal = principal(request);
        TraceEventQuery.Builder q = TraceEventQuery.builder();
        if (mintUrl != null) q.mintUrl(mintUrl);
        if (producerPubkey != null) q.producerPubkey(producerPubkey);
        if (initiatorPubkey != null) q.initiatorPubkey(initiatorPubkey);
        if (issuerId != null) q.issuerId(issuerId);
        if (issuerPubkey != null) q.issuerPubkey(issuerPubkey);
        if (voucherRef != null) q.voucherRef(voucherRef);
        if (quoteId != null) q.quoteId(quoteId);
        if (transferId != null) q.transferId(transferId);
        if (op != null) q.kind(OperationKind.fromWire(op));
        activityFilter(activity).ifPresent(q::activity);
        if (since != null) q.since(Instant.parse(since));
        if (until != null) q.until(Instant.parse(until));
        if (limit != null) q.limit(limit);
        if (cursor != null) q.cursor(cursor);

        EventPage page = queryService.listEvents(q.build());
        EventPageView view = new EventPageView(
                mapper.toViews(page.events(), principal), page.nextCursor().orElse(null));
        audit(principal, "/events", mintUrl, page.events().size(), false);
        return noStore().body(view);
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<EventView> getEvent(HttpServletRequest request,
                                              @PathVariable("eventId") String eventId) {
        TracePrincipal principal = principal(request);
        Optional<StoredEvent> event = queryService.getEvent(eventId);
        return single(principal, event, "/events/" + eventId);
    }

    @GetMapping("/operations/{operationId}")
    public ResponseEntity<EventView> getByOperation(HttpServletRequest request,
                                                    @PathVariable("operationId") String operationId) {
        TracePrincipal principal = principal(request);
        Optional<StoredEvent> event = queryService.getByOperation(operationId);
        return single(principal, event, "/operations/" + operationId);
    }

    @GetMapping("/proofs/{y}")
    public ResponseEntity<ProofHistory> proofHistory(
            HttpServletRequest request,
            @PathVariable("y") String y,
            @RequestParam(name = "mintUrl", required = false) String mintUrl,
            @RequestParam(name = "keysetId", required = false) String keysetId) {
        TracePrincipal principal = principal(request);
        ProofHistory history = queryService.proofHistory(
                Optional.ofNullable(mintUrl), Optional.ofNullable(keysetId), y);
        audit(principal, "/proofs/" + y, y, history.groups().size(), false);
        return noStore().body(history);
    }

    @GetMapping("/stats")
    public ResponseEntity<IndexStatus> stats(HttpServletRequest request) {
        TracePrincipal principal = principal(request);
        audit(principal, "/stats", null, 1, false);
        return ResponseEntity.ok(store.getIndexStatus());
    }

    private ResponseEntity<EventView> single(TracePrincipal principal,
                                             Optional<StoredEvent> event, String anchor) {
        if (event.isEmpty()) {
            audit(principal, anchor, anchor, 0, false);
            return ResponseEntity.notFound().build();
        }
        EventView view = mapper.toView(event.get(), principal);
        boolean revealed = !"minimal".equals(view.returnedPrivacyMode());
        audit(principal, anchor, anchor, 1, revealed);
        return noStore().body(view);
    }

    private static Optional<EventActivity> activityFilter(String activity) {
        if (activity == null || activity.isBlank() || "any".equalsIgnoreCase(activity)) {
            return Optional.empty();
        }
        return Optional.of("terminal".equalsIgnoreCase(activity)
                ? EventActivity.TERMINAL : EventActivity.ACTIVE);
    }

    private static TracePrincipal principal(HttpServletRequest request) {
        Object attr = request.getAttribute(TracePrincipal.ATTRIBUTE);
        if (attr instanceof TracePrincipal principal) {
            return principal;
        }
        throw new IllegalStateException("missing authenticated trace principal");
    }

    private static ResponseEntity.BodyBuilder noStore() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private static void audit(TracePrincipal principal, String endpoint, String anchor,
                              int resultSize, boolean payloadRevealed) {
        ACCESS_LOG.info("trace_read actor={} endpoint={} anchor={} result_size={} payload_revealed={}",
                principal.pubkey(), endpoint, anchor, resultSize, payloadRevealed);
    }
}
