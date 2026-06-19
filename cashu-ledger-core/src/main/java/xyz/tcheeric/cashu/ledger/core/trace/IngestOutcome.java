package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.Optional;

/**
 * The result of ingesting one raw event.
 *
 * @param status    what happened
 * @param eventId   the event id when known
 * @param rejection the reason, present only for {@link Status#REJECTED}/{@link Status#CONFLICT}
 */
public record IngestOutcome(Status status, Optional<String> eventId, Optional<IngestRejection> rejection) {

    public enum Status {
        /** Newly stored and indexed. */
        STORED,
        /** Already present (same event id); a no-op. */
        DUPLICATE,
        /** Failed validation. */
        REJECTED,
        /** Same operation id already stored with different content. */
        CONFLICT
    }

    public static IngestOutcome stored(String eventId) {
        return new IngestOutcome(Status.STORED, Optional.of(eventId), Optional.empty());
    }

    public static IngestOutcome duplicate(String eventId) {
        return new IngestOutcome(Status.DUPLICATE, Optional.of(eventId), Optional.empty());
    }

    public static IngestOutcome rejected(IngestRejection rejection) {
        return new IngestOutcome(Status.REJECTED, Optional.empty(), Optional.of(rejection));
    }

    public static IngestOutcome conflict(String eventId, IngestRejection rejection) {
        return new IngestOutcome(Status.CONFLICT, Optional.of(eventId), Optional.of(rejection));
    }
}
