package xyz.tcheeric.cashu.ledger.web.security;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal fixed-window rate limiter for the sensitive admin operations (design §7.2 —
 * rate-limited redaction-key endpoints). Allows up to {@code maxPerWindow} calls within each
 * rolling window; once exhausted, {@link #tryAcquire()} returns {@code false} until the window
 * rolls over. Intended as a per-instance guard, not a distributed limiter.
 */
public final class FixedWindowRateLimiter {

    private final int maxPerWindow;
    private final long windowMillis;
    private final AtomicLong windowStart = new AtomicLong(0);
    private final AtomicInteger count = new AtomicInteger(0);

    public FixedWindowRateLimiter(int maxPerWindow, long windowMillis) {
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowMillis;
    }

    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        if (now - windowStart.get() >= windowMillis) {
            windowStart.set(now);
            count.set(0);
        }
        if (count.get() >= maxPerWindow) {
            return false;
        }
        count.incrementAndGet();
        return true;
    }
}
