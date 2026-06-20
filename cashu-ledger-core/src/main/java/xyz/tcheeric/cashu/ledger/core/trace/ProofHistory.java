package xyz.tcheeric.cashu.ledger.core.trace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;

/**
 * The chronological history of a proof. When a lookup is scoped to a single
 * {@code (mintUrl, keysetId, y)} there is one {@link Group}; when only {@code y} is
 * given, results are grouped per {@code (mintUrl, keysetId)} rather than merged
 * across mints (design §5.5 / FR-4).
 *
 * @param y      the proof identifier queried
 * @param groups one group per distinct {@code (mintUrl, keysetId)}
 */
public record ProofHistory(String y, List<Group> groups) {

    /** A single proof's lifecycle under one mint/keyset. */
    public record Group(
            String mintUrl,
            String keysetId,
            String y,
            List<Entry> events,
            Optional<String> originEventId,
            Optional<String> terminalEventId,
            boolean currentlySpent
    ) {
    }

    /** One event in a proof's history, with the proof's role in that event. */
    public record Entry(String eventId, OperationKind kind, String role, Instant transitionAt) {
    }
}
