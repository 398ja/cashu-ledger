package xyz.tcheeric.cashu.ledger.trace.core;

import java.time.Instant;
import java.util.Optional;

/**
 * The health of the ledger's storage/index, surfaced to operators and used to
 * decide whether queries can be served (design §5.4: a missing or rebuilding
 * sidecar returns {@code 503} until rebuild completes).
 *
 * @param available           whether the index can serve queries
 * @param rebuilding          whether a rebuild is in progress
 * @param indexedEventCount   number of events currently indexed
 * @param latestTransitionAt  newest indexed event's {@code transition_at}, if any
 */
public record IndexStatus(
        boolean available,
        boolean rebuilding,
        long indexedEventCount,
        Optional<Instant> latestTransitionAt
) {

    public IndexStatus {
        latestTransitionAt = latestTransitionAt == null ? Optional.empty() : latestTransitionAt;
    }
}
