package xyz.tcheeric.cashu.ledger.web.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Bounded in-memory ring buffer of recent trace read-access audit entries (design §7.1 /
 * FR-031). Every read records who accessed what and whether a secret-bearing payload was
 * revealed; {@code /admin/access-log} streams the most recent entries from here. The buffer
 * is capped so it never grows unbounded.
 */
@Component
public final class TraceAccessAuditSink {

    /** One audited read access. */
    public record AccessEntry(long atMs, String actor, String endpoint, String anchor,
                              int resultSize, boolean payloadRevealed) {
    }

    private static final int CAPACITY = 1000;

    private final Deque<AccessEntry> entries = new ArrayDeque<>();

    public synchronized void record(AccessEntry entry) {
        if (entries.size() >= CAPACITY) {
            entries.removeFirst();
        }
        entries.addLast(entry);
    }

    /** The most recent entries, newest first, capped at {@code limit}. */
    public synchronized List<AccessEntry> recent(int limit) {
        List<AccessEntry> snapshot = new ArrayList<>(entries);
        List<AccessEntry> out = new ArrayList<>(Math.min(limit, snapshot.size()));
        for (int i = snapshot.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(snapshot.get(i));
        }
        return out;
    }
}
