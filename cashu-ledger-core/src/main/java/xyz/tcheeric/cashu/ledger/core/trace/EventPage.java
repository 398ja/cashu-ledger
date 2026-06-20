package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.List;
import java.util.Optional;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;

/**
 * One page of a cursor-paginated listing.
 *
 * @param events     the events on this page (newest first)
 * @param nextCursor an opaque cursor for the next page, or empty at end of stream
 */
public record EventPage(List<StoredEvent> events, Optional<String> nextCursor) {
}
