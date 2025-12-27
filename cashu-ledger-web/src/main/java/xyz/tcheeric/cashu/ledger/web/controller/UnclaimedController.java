package xyz.tcheeric.cashu.ledger.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.state.ReclaimOutcome;
import xyz.tcheeric.cashu.ledger.core.state.UnclaimedStatusResult;

import java.util.List;

@RestController
@RequestMapping("/api/v1/unclaimed")
public class UnclaimedController {

    private final VoucherLedgerService ledgerService;

    public UnclaimedController(VoucherLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping
    public ResponseEntity<List<VoucherNode>> list(
            @RequestParam(value = "sentBy", required = false) String sentBy,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(ledgerService.listUnclaimed(sentBy, limit));
    }

    @GetMapping("/check")
    public ResponseEntity<UnclaimedStatusResult> check(
            @RequestParam("voucherId") String voucherId,
            @RequestParam(value = "tokenFile", required = false) String tokenFile
    ) {
        return ResponseEntity.ok(ledgerService.checkUnclaimedStatus(voucherId, tokenFile));
    }

    @GetMapping("/reclaim")
    public ResponseEntity<ReclaimOutcome> reclaim(
            @RequestParam("voucherId") String voucherId,
            @RequestParam(value = "tokenFile", required = false) String tokenFile
    ) {
        return ResponseEntity.ok(ledgerService.reclaim(voucherId, tokenFile));
    }
}
