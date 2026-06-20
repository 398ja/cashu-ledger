package xyz.tcheeric.cashu.ledger.web.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Raised when a caller without the {@code trace:admin} authority invokes an admin endpoint.
 * Mapped to HTTP 403 (design §7.3).
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class AdminForbiddenException extends RuntimeException {

    public AdminForbiddenException() {
        super("trace:admin authority required");
    }
}
