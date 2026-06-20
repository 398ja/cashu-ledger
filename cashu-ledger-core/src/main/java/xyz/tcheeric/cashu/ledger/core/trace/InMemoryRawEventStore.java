package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;

/**
 * In-memory {@link RawEventStore} for tests and small deployments. Not durable.
 */
public final class InMemoryRawEventStore implements RawEventStore {

    private final Map<String, StoredEvent> events = new ConcurrentHashMap<>();

    @Override
    public boolean store(StoredEvent event) {
        String eventId = requireEventId(event);
        return events.putIfAbsent(eventId, event) == null;
    }

    @Override
    public Optional<StoredEvent> findByEventId(String eventId) {
        return Optional.ofNullable(events.get(eventId));
    }

    @Override
    public boolean remove(String eventId) {
        return events.remove(eventId) != null;
    }

    @Override
    public long count() {
        return events.size();
    }

    private static String requireEventId(StoredEvent event) {
        return event.event().eventId().orElseThrow(() ->
                new IllegalArgumentException("stored event must have a present eventId"));
    }
}
