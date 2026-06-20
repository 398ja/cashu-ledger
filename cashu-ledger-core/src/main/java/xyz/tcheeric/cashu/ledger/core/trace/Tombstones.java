package xyz.tcheeric.cashu.ledger.core.trace;

/**
 * Tells the walk whether an event has been pruned, so a reached pruned hop is rendered as a
 * tombstone placeholder and counted rather than expanded (design §5.11). The sidecar index
 * implements this via {@code isTombstoned}.
 */
@FunctionalInterface
public interface Tombstones {

    /** No events are tombstoned. */
    Tombstones NONE = eventId -> false;

    boolean isTombstoned(String eventId);
}
