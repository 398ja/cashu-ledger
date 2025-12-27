package xyz.tcheeric.cashu.ledger.web.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/watch")
public class WatchController {

    private final VoucherLedgerService ledgerService;

    public WatchController(VoucherLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping(value = "/{voucherId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watch(@PathVariable("voucherId") String voucherId) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        new Thread(() -> pollVoucher(voucherId, emitter)).start();
        return emitter;
    }

    private void pollVoucher(String voucherId, SseEmitter emitter) {
        try {
            Optional<VoucherNode> previous = ledgerService.fetchVoucher(voucherId);
            if (previous.isEmpty()) {
                emitter.send(SseEmitter.event().name("status").data("not_found"));
                emitter.complete();
                return;
            }
            emitter.send(SseEmitter.event().name("status").data(status(previous.get())));

            int polls = 0;
            while (polls < 60) {
                Thread.sleep(5000);
                polls++;
                Optional<VoucherNode> current = ledgerService.fetchVoucher(voucherId);
                if (current.isEmpty()) {
                    emitter.send(SseEmitter.event().name("status").data("not_found"));
                    emitter.complete();
                    return;
                }
                String prevStatus = status(previous.get());
                String nowStatus = status(current.get());
                if (!prevStatus.equals(nowStatus)) {
                    emitter.send(SseEmitter.event().name("status").data(prevStatus + "->" + nowStatus));
                    previous = current;
                }
            }
            emitter.complete();
        } catch (IOException | InterruptedException e) {
            emitter.completeWithError(e);
            Thread.currentThread().interrupt();
        }
    }

    private String status(VoucherNode node) {
        return node.status() == null ? "unknown" : node.status().name().toLowerCase();
    }
}
