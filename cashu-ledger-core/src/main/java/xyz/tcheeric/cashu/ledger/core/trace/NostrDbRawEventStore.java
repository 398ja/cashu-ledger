package xyz.tcheeric.cashu.ledger.core.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.nostrdb.Filter;
import xyz.tcheeric.nostrdb.Ndb;
import xyz.tcheeric.nostrdb.Note;
import xyz.tcheeric.nostrdb.Transaction;

/**
 * Durable {@link RawEventStore} backed by nostrdb — the system of record for the signed
 * kind-9079 events (design §5.4 / §6.1). Events are persisted verbatim via {@code processEvent}
 * and read back by id, re-parsed to a {@link StoredEvent} on the way out.
 *
 * <p>nostrdb is an append-only LMDB log with no per-note delete in the 0.3.0 binding, so
 * {@link #remove(String)} is a no-op: pruning records a tombstone in the sidecar (which drives
 * walk rendering), but reclaiming the raw payload requires off-system archival and an offline
 * database compaction. {@link #reindex} re-projects the surviving events into a sidecar for
 * startup rebuild.</p>
 */
public final class NostrDbRawEventStore implements RawEventStore, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NostrDbRawEventStore.class);
    private static final int TRACE_KIND = NostrEventMetadata.TRACE_EVENT_KIND;
    private static final int REINDEX_BATCH = 100_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Ndb ndb;
    private final TraceEventMapper mapper;

    public NostrDbRawEventStore(Ndb ndb, TraceEventMapper mapper) {
        this.ndb = ndb;
        this.mapper = mapper;
    }

    /** Opens (or creates) a nostrdb at {@code dbPath}. */
    public static NostrDbRawEventStore open(Path dbPath) {
        return new NostrDbRawEventStore(Ndb.open(dbPath), new TraceEventMapper());
    }

    @Override
    public boolean store(StoredEvent event) {
        String raw = event.rawEventJson().orElseThrow(() -> new IllegalArgumentException(
                "nostrdb store requires the raw signed event JSON; got a parsed-only event"));
        String eventId = event.event().eventId().orElseThrow(() ->
                new IllegalArgumentException("stored event must have a present eventId"));
        if (findByEventId(eventId).isPresent()) {
            return false;
        }
        ndb.processEvent(raw);
        return true;
    }

    @Override
    public Optional<StoredEvent> findByEventId(String eventId) {
        try (Transaction txn = ndb.beginTransaction()) {
            return ndb.getNoteById(txn, eventId).map(this::toStoredEvent);
        }
    }

    @Override
    public boolean remove(String eventId) {
        LOGGER.warn("trace_raw_remove_unsupported event_id={} reason=nostrdb_append_only", eventId);
        return false;
    }

    @Override
    public long count() {
        return ndb.getStats().totalEventCount();
    }

    /**
     * Re-projects all stored kind-9079 events into {@code index} (startup rebuild after sidecar
     * loss or drift). Returns the number of events re-indexed.
     */
    public int reindex(SqliteSidecarIndex index) {
        int reindexed = 0;
        try (Transaction txn = ndb.beginTransaction();
             Filter filter = Filter.builder().kinds(TRACE_KIND).build()) {
            List<Note> notes = ndb.queryNotes(txn, filter, REINDEX_BATCH);
            for (Note note : notes) {
                try {
                    index.index(toStoredEvent(note).event());
                    reindexed++;
                } catch (RuntimeException e) {
                    LOGGER.warn("trace_reindex_skip event_id={} error={}", note.id(), e.getMessage());
                }
            }
        }
        LOGGER.info("trace_reindex_completed reindexed={}", reindexed);
        return reindexed;
    }

    private StoredEvent toStoredEvent(Note note) {
        // Reconstruct canonical NIP-01 JSON from the note's fields (the binding's toJson() omits
        // the id), then re-parse to a TransactionEvent.
        ObjectNode root = JSON.createObjectNode();
        root.put("id", note.id());
        root.put("pubkey", note.pubkey());
        root.put("created_at", note.createdAt());
        root.put("kind", note.kind());
        ArrayNode tagsNode = root.putArray("tags");
        for (List<String> tag : note.tags()) {
            ArrayNode t = tagsNode.addArray();
            tag.forEach(t::add);
        }
        root.put("content", note.content());
        root.put("sig", note.sig());
        String raw;
        try {
            raw = JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new TraceStorageException("Failed to serialise nostrdb note " + note.id(), e);
        }
        return new StoredEvent(mapper.parse(raw, "nostrdb").event(), Optional.of(raw));
    }

    @Override
    public void close() {
        ndb.close();
    }
}
