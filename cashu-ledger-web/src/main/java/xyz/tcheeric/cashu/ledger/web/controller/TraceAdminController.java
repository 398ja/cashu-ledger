package xyz.tcheeric.cashu.ledger.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.cashu.ledger.core.trace.RedactionKeyMetadata;
import xyz.tcheeric.cashu.ledger.core.trace.RedactionKeyRegistry;
import xyz.tcheeric.cashu.ledger.trace.core.IndexStatus;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.web.security.FixedWindowRateLimiter;
import xyz.tcheeric.cashu.ledger.web.security.TracePrincipal;
import xyz.tcheeric.cashu.ledger.web.trace.TraceAccessAuditSink;

/**
 * Admin surface for the trace ledger (design §7.2 / §5.4), gated by the {@code trace:admin}
 * authority: redaction-key registration/listing/verification (keys held only as ciphertext),
 * the read-access audit log captured per FR-031, and index health. The sensitive key
 * operations are additionally rate-limited per instance.
 */
@RestController
@RequestMapping("/api/v1/trace/admin")
public class TraceAdminController {

    private static final HexFormat HEX = HexFormat.of();

    private final RedactionKeyRegistry redactionKeys;
    private final TraceAccessAuditSink auditSink;
    private final TraceEventStore store;
    private final FixedWindowRateLimiter keyOpsLimiter = new FixedWindowRateLimiter(20, 60_000);

    public TraceAdminController(RedactionKeyRegistry redactionKeys, TraceAccessAuditSink auditSink,
                               TraceEventStore store) {
        this.redactionKeys = redactionKeys;
        this.auditSink = auditSink;
        this.store = store;
    }

    public record RegisterKeyRequest(String keyId, String label, String keyHex) {
    }

    public record VerifyKeyRequest(String keyHex) {
    }

    @GetMapping("/redaction-keys")
    public ResponseEntity<List<RedactionKeyMetadata>> listKeys(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(redactionKeys.list());
    }

    @PostMapping("/redaction-keys")
    public ResponseEntity<?> registerKey(HttpServletRequest request,
                                         @RequestBody RegisterKeyRequest body) {
        requireAdmin(request);
        if (!keyOpsLimiter.tryAcquire()) {
            return rateLimited();
        }
        try {
            RedactionKeyMetadata metadata = redactionKeys.register(
                    body.keyId(), body.label() == null ? "" : body.label(), HEX.parseHex(body.keyHex()));
            return ResponseEntity.status(HttpStatus.CREATED).body(metadata);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/redaction-keys/{id}/verify")
    public ResponseEntity<?> verifyKey(HttpServletRequest request, @PathVariable("id") String id,
                                       @RequestBody VerifyKeyRequest body) {
        requireAdmin(request);
        if (!keyOpsLimiter.tryAcquire()) {
            return rateLimited();
        }
        boolean verified = redactionKeys.verify(id, HEX.parseHex(body.keyHex()));
        return ResponseEntity.ok(Map.of("keyId", id, "verified", verified));
    }

    @GetMapping("/access-log")
    public ResponseEntity<List<TraceAccessAuditSink.AccessEntry>> accessLog(
            HttpServletRequest request,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit) {
        requireAdmin(request);
        return ResponseEntity.ok(auditSink.recent(Math.min(Math.max(limit, 1), 1000)));
    }

    @GetMapping("/index-status")
    public ResponseEntity<IndexStatus> indexStatus(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(store.getIndexStatus());
    }

    private static void requireAdmin(HttpServletRequest request) {
        Object attr = request.getAttribute(TracePrincipal.ATTRIBUTE);
        if (!(attr instanceof TracePrincipal principal) || !principal.isAdmin()) {
            throw new AdminForbiddenException();
        }
    }

    private static ResponseEntity<Object> rateLimited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "redaction-key operations are rate-limited; retry shortly"));
    }
}
