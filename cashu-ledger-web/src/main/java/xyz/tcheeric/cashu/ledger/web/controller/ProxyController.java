package xyz.tcheeric.cashu.ledger.web.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import xyz.tcheeric.cashu.ledger.core.model.TraversalDirection;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.state.HistoryResult;
import xyz.tcheeric.cashu.ledger.core.state.VerificationReport;
import xyz.tcheeric.cashu.ledger.core.state.VoucherSearchCriteria;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Proxy-style endpoints that delegate to the local VoucherLedgerService.
 * Provides /proxy/* endpoints for frontend compatibility while using
 * the same underlying service as /api/v1/vouchers/*.
 */
@RestController
@RequestMapping("/proxy")
public class ProxyController {

    private static final int WATCH_POLL_SECONDS = 5;
    private static final int WATCH_DURATION_MINUTES = 5;

    private final VoucherLedgerService ledgerService;
    private final ScheduledExecutorService watchExecutor;

    public ProxyController(VoucherLedgerService ledgerService) {
        this.ledgerService = ledgerService;
        this.watchExecutor = Executors.newScheduledThreadPool(2);
    }

    @GetMapping("/vouchers/{id}")
    public ResponseEntity<VoucherNode> proxyInspect(@PathVariable("id") String id) {
        return ledgerService.fetchVoucher(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/vouchers/{id}/tree")
    public ResponseEntity<VoucherTree> proxyTree(
            @PathVariable("id") String id,
            @RequestParam(value = "depth", defaultValue = "10") int depth,
            @RequestParam(value = "direction", defaultValue = "both") String direction
    ) {
        TraversalDirection dir = parseDirection(direction);
        return ledgerService.buildTree(id, depth, dir)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/vouchers/{id}/history")
    public ResponseEntity<HistoryResult> proxyHistory(
            @PathVariable("id") String id,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        HistoryResult history = ledgerService.fetchHistory(
                id,
                parseInstant(since),
                parseInstant(until),
                limit
        );
        return ResponseEntity.ok(history);
    }

    @GetMapping("/vouchers/{id}/verify")
    public ResponseEntity<VerificationReport> proxyVerify(@PathVariable("id") String id) {
        VerificationReport report = ledgerService.verify(id);
        if (!report.found()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/vouchers/{left}/diff/{right}")
    public ResponseEntity<List<VoucherNode>> proxyDiff(
            @PathVariable("left") String left,
            @PathVariable("right") String right
    ) {
        Optional<VoucherNode> leftNode = ledgerService.fetchVoucher(left);
        Optional<VoucherNode> rightNode = ledgerService.fetchVoucher(right);
        if (leftNode.isEmpty() || rightNode.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(List.of(leftNode.get(), rightNode.get()));
    }

    @GetMapping("/vouchers")
    public ResponseEntity<List<VoucherNode>> proxySearch(
            @RequestParam(value = "issuer_id", required = false) String issuerId,
            @RequestParam(value = "issuer", required = false) String issuer,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "unclaimed", defaultValue = "false") boolean unclaimed
    ) {
        // Support both issuer_id and issuer parameter names
        String issuerValue = issuerId != null ? issuerId : issuer;
        VoucherSearchCriteria criteria = new VoucherSearchCriteria(
                issuerValue,
                parseStatus(status),
                parseInstant(since),
                parseInstant(until),
                limit,
                unclaimed
        );
        return ResponseEntity.ok(ledgerService.search(criteria));
    }

    @GetMapping(value = "/watch/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter proxyWatch(@PathVariable("id") String id) {
        SseEmitter emitter = new SseEmitter(WATCH_DURATION_MINUTES * 60 * 1000L);

        watchExecutor.scheduleAtFixedRate(() -> {
            try {
                Optional<VoucherNode> voucher = ledgerService.fetchVoucher(id);
                if (voucher.isPresent()) {
                    emitter.send(SseEmitter.event()
                            .name("voucher")
                            .data(voucher.get()));
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                // Log and continue polling
            }
        }, 0, WATCH_POLL_SECONDS, TimeUnit.SECONDS);

        // Complete after watch duration
        watchExecutor.schedule(emitter::complete, WATCH_DURATION_MINUTES, TimeUnit.MINUTES);

        emitter.onCompletion(() -> {});
        emitter.onTimeout(emitter::complete);

        return emitter;
    }

    private TraversalDirection parseDirection(String direction) {
        return switch (direction.toLowerCase()) {
            case "up" -> TraversalDirection.UP;
            case "down" -> TraversalDirection.DOWN;
            default -> TraversalDirection.BOTH;
        };
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    private VoucherStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return VoucherStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
