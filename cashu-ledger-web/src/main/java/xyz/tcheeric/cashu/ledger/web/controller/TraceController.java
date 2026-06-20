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
import java.util.List;
import java.util.Map;
import xyz.tcheeric.cashu.ledger.core.trace.EventPage;
import xyz.tcheeric.cashu.ledger.core.trace.ProofCandidate;
import xyz.tcheeric.cashu.ledger.core.trace.ProofHistory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import xyz.tcheeric.cashu.ledger.core.trace.QuoteStatusService;
import xyz.tcheeric.cashu.ledger.core.trace.QuoteStatusView;
import xyz.tcheeric.cashu.ledger.core.trace.TraceIngestService;
import xyz.tcheeric.cashu.ledger.core.trace.TraceQueryService;
import xyz.tcheeric.cashu.ledger.core.trace.VisualisationGraph;
import xyz.tcheeric.cashu.ledger.core.trace.VisualisationService;
import xyz.tcheeric.cashu.ledger.core.trace.WalkResult;
import xyz.tcheeric.cashu.ledger.core.trace.WalkService;
import xyz.tcheeric.cashu.ledger.trace.core.EventActivity;
import xyz.tcheeric.cashu.ledger.trace.core.IndexStatus;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.SchemaCompatibility;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventQuery;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.web.config.WebLedgerProperties;
import xyz.tcheeric.cashu.ledger.web.security.TracePrincipal;
import xyz.tcheeric.cashu.ledger.web.trace.EventPageView;
import xyz.tcheeric.cashu.ledger.web.trace.EventView;
import xyz.tcheeric.cashu.ledger.web.trace.RelaysView;
import xyz.tcheeric.cashu.ledger.web.trace.StatsView;
import xyz.tcheeric.cashu.ledger.web.trace.TraceAccessAuditSink;
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
    private final WalkService walkService;
    private final VisualisationService visualisationService;
    private final WebLedgerProperties ledgerProperties;
    private final TraceAccessAuditSink auditSink;
    private final ObjectProvider<TraceIngestService> ingestServiceProvider;
    private final MeterRegistry meterRegistry;
    private final QuoteStatusService quoteStatusService;

    public TraceController(TraceQueryService queryService, TraceEventStore store,
                          TraceResponseMapper mapper, WalkService walkService,
                          VisualisationService visualisationService,
                          WebLedgerProperties ledgerProperties,
                          TraceAccessAuditSink auditSink,
                          ObjectProvider<TraceIngestService> ingestServiceProvider,
                          MeterRegistry meterRegistry,
                          QuoteStatusService quoteStatusService) {
        this.queryService = queryService;
        this.store = store;
        this.mapper = mapper;
        this.walkService = walkService;
        this.visualisationService = visualisationService;
        this.ledgerProperties = ledgerProperties;
        this.auditSink = auditSink;
        this.ingestServiceProvider = ingestServiceProvider;
        this.meterRegistry = meterRegistry;
        this.quoteStatusService = quoteStatusService;
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

    @GetMapping("/proofs/{y}/walk")
    public ResponseEntity<Object> proofWalk(
            HttpServletRequest request,
            @PathVariable("y") String y,
            @RequestParam(name = "mintUrl", required = false) String mintUrl,
            @RequestParam(name = "keysetId", required = false) String keysetId,
            @RequestParam(name = "direction", required = false, defaultValue = "down") String direction,
            @RequestParam(name = "depth", required = false, defaultValue = "10") int depth,
            @RequestParam(name = "limit", required = false, defaultValue = "1000") int limit) {

        TracePrincipal principal = principal(request);
        WalkService.Direction dir = walkDirection(direction);

        ProofCandidate target;
        if (mintUrl != null && keysetId != null) {
            target = new ProofCandidate(mintUrl, keysetId);
        } else {
            List<ProofCandidate> candidates = matchingCandidates(y, mintUrl);
            if (candidates.size() > 1) {
                audit(principal, "/proofs/" + y + "/walk", y, candidates.size(), false);
                return ResponseEntity.status(409).header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(Map.of(
                                "error", "AMBIGUOUS_PROOF",
                                "message", "Proof y resolves to multiple (mintUrl, keysetId) pairs; "
                                        + "supply both to disambiguate.",
                                "candidates", candidates));
            }
            if (candidates.isEmpty()) {
                WalkResult empty = new WalkResult(dir.name().toLowerCase(), depth,
                        List.of(), List.of(), false, Optional.empty(), 0);
                audit(principal, "/proofs/" + y + "/walk", y, 0, false);
                return noStore().body(empty);
            }
            target = candidates.get(0);
        }

        final ProofCandidate resolved = target;
        WalkResult result = timed("cashu_trace_walk_seconds", () -> walkService.walkFromProof(
                resolved.mintUrl(), resolved.keysetId(), y, dir, depth, limit));
        audit(principal, "/proofs/" + y + "/walk", y, result.nodes().size(), false);
        return noStore().body(result);
    }

    @GetMapping("/vouchers/{voucherId}/events")
    public ResponseEntity<EventPageView> voucherEvents(
            HttpServletRequest request,
            @PathVariable("voucherId") String voucherId,
            @RequestParam(name = "activity", required = false) String activity,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        TracePrincipal principal = principal(request);
        EventPage page = queryService.findByVoucherRef(voucherId, activityFilter(activity),
                pageLimit(limit), Optional.ofNullable(cursor));
        return pageResponse(principal, page, "/vouchers/" + voucherId + "/events", voucherId);
    }

    @GetMapping("/issuers/{issuerId}/events")
    public ResponseEntity<EventPageView> issuerEvents(
            HttpServletRequest request,
            @PathVariable("issuerId") String issuerId,
            @RequestParam(name = "byPubkey", required = false, defaultValue = "false") boolean byPubkey,
            @RequestParam(name = "activity", required = false) String activity,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        TracePrincipal principal = principal(request);
        Optional<EventActivity> activityFilter = activityFilter(activity);
        Optional<String> cursorValue = Optional.ofNullable(cursor);
        EventPage page = byPubkey
                ? queryService.findByIssuerPubkey(issuerId, activityFilter, pageLimit(limit), cursorValue)
                : queryService.findByIssuerId(issuerId, activityFilter, pageLimit(limit), cursorValue);
        return pageResponse(principal, page, "/issuers/" + issuerId + "/events", issuerId);
    }

    @GetMapping("/visualisation")
    public ResponseEntity<VisualisationGraph> visualisation(
            HttpServletRequest request,
            @RequestParam(name = "eventId", required = false) String eventId,
            @RequestParam(name = "y", required = false) String y,
            @RequestParam(name = "mintUrl", required = false) String mintUrl,
            @RequestParam(name = "keysetId", required = false) String keysetId,
            @RequestParam(name = "direction", required = false, defaultValue = "both") String direction,
            @RequestParam(name = "depth", required = false, defaultValue = "10") int depth,
            @RequestParam(name = "limit", required = false, defaultValue = "1000") int limit) {

        TracePrincipal principal = principal(request);
        WalkService.Direction dir = walkDirection(direction);
        VisualisationGraph graph = timed("cashu_trace_visualisation_seconds", () -> {
            WalkResult walk = anchoredWalk(eventId, y, mintUrl, keysetId, dir, depth, limit);
            return visualisationService.fromWalk(walk);
        });
        int nodeCount = graph.mints().stream().mapToInt(m -> m.nodes().size()).sum();
        audit(principal, "/visualisation", eventId != null ? eventId : y, nodeCount, false);
        return noStore().body(graph);
    }

    @GetMapping("/quotes/{quoteId}/status")
    public ResponseEntity<QuoteStatusView> quoteStatus(
            HttpServletRequest request,
            @PathVariable("quoteId") String quoteId,
            @RequestParam(name = "mintUrl") String mintUrl) {
        TracePrincipal principal = principal(request);
        Optional<QuoteStatusView> status = quoteStatusService.status(mintUrl, quoteId);
        audit(principal, "/quotes/" + quoteId + "/status", quoteId, status.isPresent() ? 1 : 0, false);
        return status.map(view -> noStore().body(view))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/quotes/{quoteId}/events")
    public ResponseEntity<EventPageView> quoteEvents(
            HttpServletRequest request,
            @PathVariable("quoteId") String quoteId,
            @RequestParam(name = "mintUrl") String mintUrl,
            @RequestParam(name = "activity", required = false) String activity,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        TracePrincipal principal = principal(request);
        EventPage page = queryService.quoteEvents(mintUrl, quoteId, activityFilter(activity),
                pageLimit(limit), Optional.ofNullable(cursor));
        return pageResponse(principal, page, "/quotes/" + quoteId + "/events", quoteId);
    }

    @GetMapping("/stats")
    public ResponseEntity<StatsView> stats(HttpServletRequest request) {
        TracePrincipal principal = principal(request);
        IndexStatus status = store.getIndexStatus();
        StatsView view = new StatsView(
                status.available(), status.rebuilding(), status.indexedEventCount(),
                status.latestTransitionAt().map(Instant::toString).orElse(null),
                TransactionEvent.CURRENT_SCHEMA_VERSION, ingestCounters());
        audit(principal, "/stats", null, 1, false);
        return ResponseEntity.ok(view);
    }

    @GetMapping("/relays")
    public ResponseEntity<RelaysView> relays(HttpServletRequest request) {
        TracePrincipal principal = principal(request);
        int current = TransactionEvent.CURRENT_SCHEMA_VERSION;
        RelaysView view = new RelaysView(
                ledgerProperties.getRelays(),
                current,
                SchemaCompatibility.supportedVersions(current),
                SchemaCompatibility.deprecatedVersions(current));
        audit(principal, "/relays", null, ledgerProperties.getRelays().size(), false);
        return ResponseEntity.ok(view);
    }

    private <T> T timed(String metric, Supplier<T> work) {
        return Timer.builder(metric).register(meterRegistry).record(work);
    }

    private StatsView.IngestCounters ingestCounters() {
        TraceIngestService ingest = ingestServiceProvider.getIfAvailable();
        if (ingest == null) {
            return null;
        }
        var metrics = ingest.metrics();
        return new StatsView.IngestCounters(metrics.stored(), metrics.duplicates(),
                metrics.rejected(), metrics.conflicts());
    }

    private WalkResult anchoredWalk(String eventId, String y, String mintUrl, String keysetId,
                                    WalkService.Direction dir, int depth, int limit) {
        if (eventId != null) {
            return walkService.walkFromEvent(eventId, dir, depth, limit);
        }
        if (y == null) {
            return new WalkResult(dir.name().toLowerCase(), depth,
                    List.of(), List.of(), false, Optional.empty(), 0);
        }
        ProofCandidate target;
        if (mintUrl != null && keysetId != null) {
            target = new ProofCandidate(mintUrl, keysetId);
        } else {
            List<ProofCandidate> candidates = matchingCandidates(y, mintUrl);
            if (candidates.size() != 1) {
                return new WalkResult(dir.name().toLowerCase(), depth,
                        List.of(), List.of(), false, Optional.empty(), 0);
            }
            target = candidates.get(0);
        }
        return walkService.walkFromProof(target.mintUrl(), target.keysetId(), y, dir, depth, limit);
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

    private ResponseEntity<EventPageView> pageResponse(TracePrincipal principal, EventPage page,
                                                       String endpoint, String anchor) {
        EventPageView view = new EventPageView(
                mapper.toViews(page.events(), principal), page.nextCursor().orElse(null));
        boolean revealed = view.events().stream().anyMatch(v -> !"minimal".equals(v.returnedPrivacyMode()));
        audit(principal, endpoint, anchor, page.events().size(), revealed);
        return noStore().body(view);
    }

    private static int pageLimit(Integer limit) {
        return limit != null ? limit : TraceEventQuery.DEFAULT_LIMIT;
    }

    private List<ProofCandidate> matchingCandidates(String y, String mintUrl) {
        List<ProofCandidate> candidates = queryService.candidatesForY(y);
        if (mintUrl == null) {
            return candidates;
        }
        return candidates.stream().filter(c -> mintUrl.equals(c.mintUrl())).toList();
    }

    private static WalkService.Direction walkDirection(String direction) {
        return switch (direction.toLowerCase()) {
            case "up" -> WalkService.Direction.UP;
            case "both" -> WalkService.Direction.BOTH;
            default -> WalkService.Direction.DOWN;
        };
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

    private void audit(TracePrincipal principal, String endpoint, String anchor,
                       int resultSize, boolean payloadRevealed) {
        ACCESS_LOG.info("trace_read actor={} endpoint={} anchor={} result_size={} payload_revealed={}",
                principal.pubkey(), endpoint, anchor, resultSize, payloadRevealed);
        auditSink.record(new TraceAccessAuditSink.AccessEntry(
                System.currentTimeMillis(), principal.pubkey(), endpoint, anchor,
                resultSize, payloadRevealed));
    }
}
