package xyz.tcheeric.cashu.ledger.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.cashu.ledger.core.model.TraversalDirection;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.state.HistoryResult;
import xyz.tcheeric.cashu.ledger.core.state.StatusChange;
import xyz.tcheeric.cashu.ledger.core.state.VoucherSearchCriteria;
import xyz.tcheeric.cashu.ledger.core.state.VerificationReport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/vouchers")
public class VoucherController {

    private final VoucherLedgerService ledgerService;

    public VoucherController(VoucherLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<VoucherNode> inspect(@PathVariable("id") String voucherId) {
        return ledgerService.fetchVoucher(voucherId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/tree")
    public ResponseEntity<VoucherTree> tree(
            @PathVariable("id") String voucherId,
            @RequestParam(value = "depth", defaultValue = "10") int depth,
            @RequestParam(value = "direction", defaultValue = "both") String direction
    ) {
        TraversalDirection dir = switch (direction.toLowerCase()) {
            case "up" -> TraversalDirection.UP;
            case "down" -> TraversalDirection.DOWN;
            default -> TraversalDirection.BOTH;
        };
        Optional<VoucherTree> tree = ledgerService.buildTree(voucherId, depth, dir);
        return tree.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<HistoryResult> history(
            @PathVariable("id") String voucherId,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        HistoryResult history = ledgerService.fetchHistory(
                voucherId,
                parseInstant(since),
                parseInstant(until),
                limit
        );
        return ResponseEntity.ok(history);
    }

    @GetMapping("/{id}/verify")
    public ResponseEntity<VerificationReport> verify(@PathVariable("id") String voucherId) {
        VerificationReport report = ledgerService.verify(voucherId);
        if (!report.found()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/{id}/diff/{otherId}")
    public ResponseEntity<List<VoucherNode>> diff(
            @PathVariable("id") String voucherId,
            @PathVariable("otherId") String otherId
    ) {
        Optional<VoucherNode> left = ledgerService.fetchVoucher(voucherId);
        Optional<VoucherNode> right = ledgerService.fetchVoucher(otherId);
        if (left.isEmpty() || right.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(List.of(left.get(), right.get()));
    }

    @GetMapping
    public ResponseEntity<List<VoucherNode>> search(
            @RequestParam(value = "issuer", required = false) String issuer,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "unclaimed", defaultValue = "false") boolean unclaimed
    ) {
        VoucherSearchCriteria criteria = new VoucherSearchCriteria(
                issuer,
                parseStatus(status),
                parseInstant(since),
                parseInstant(until),
                limit,
                unclaimed
        );
        return ResponseEntity.ok(ledgerService.search(criteria));
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
