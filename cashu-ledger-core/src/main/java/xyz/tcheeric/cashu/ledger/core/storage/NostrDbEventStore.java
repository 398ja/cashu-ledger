package xyz.tcheeric.cashu.ledger.core.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import nostr.event.impl.GenericEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.nostrdb.Filter;
import xyz.tcheeric.nostrdb.Ndb;
import xyz.tcheeric.nostrdb.Note;
import xyz.tcheeric.nostrdb.Transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event store implementation backed by nostrdb-jni.
 *
 * <p>Provides high-performance persistent storage for Nostr events using
 * LMDB (Lightning Memory-Mapped Database). The store is thread-safe with
 * the constraint that transactions must not be shared across threads.
 */
public class NostrDbEventStore implements EventStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(NostrDbEventStore.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int VOUCHER_KIND = 30078;
    private static final String VOUCHER_D_TAG_PREFIX = "voucher:";
    /** nostrdb caps query limits at 100,000,000; use it as the "all rows" bound. */
    private static final int MAX_QUERY_LIMIT = 100_000_000;

    private final EventStoreConfig config;
    private final Ndb ndb;
    private final boolean available;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Track relay URLs for events (nostrdb doesn't store relay metadata)
    private final Map<String, String> eventRelayMap = new ConcurrentHashMap<>();

    /**
     * Creates a new event store with the specified configuration.
     *
     * @param config the store configuration
     */
    public NostrDbEventStore(EventStoreConfig config) {
        this.config = config;

        Ndb tempNdb = null;
        boolean tempAvailable = false;

        try {
            Path dbPath = config.databasePath();
            if (!Files.exists(dbPath)) {
                Files.createDirectories(dbPath);
                LOGGER.info("event_store_directory_created path={}", dbPath);
            }

            tempNdb = Ndb.open(dbPath);
            tempAvailable = true;
            LOGGER.info("event_store_opened path={}", dbPath);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            LOGGER.warn("nostrdb_native_unavailable platform={} error={}",
                    System.getProperty("os.name"), e.getMessage());
        } catch (IOException e) {
            LOGGER.error("event_store_directory_creation_failed path={} error={}",
                    config.databasePath(), e.getMessage());
        } catch (Exception e) {
            LOGGER.error("event_store_open_failed path={} error={}",
                    config.databasePath(), e.getMessage(), e);
        }

        this.ndb = tempNdb;
        this.available = tempAvailable;
    }

    @Override
    public boolean store(GenericEvent event, String relayUrl) {
        if (!available || event == null) {
            return false;
        }

        try {
            String eventJson = OBJECT_MAPPER.writeValueAsString(toSerializableEvent(event));
            ndb.processEvent(eventJson);

            if (event.getId() != null && relayUrl != null) {
                eventRelayMap.put(event.getId(), relayUrl);
            }

            LOGGER.debug("event_stored event_id={} kind={} relay={}",
                    event.getId(), event.getKind(), relayUrl);
            return true;
        } catch (JsonProcessingException e) {
            LOGGER.warn("event_serialization_failed event_id={} error={}",
                    event.getId(), e.getMessage());
            return false;
        } catch (Exception e) {
            // Duplicate events are expected, log at debug level
            LOGGER.debug("event_store_failed event_id={} error={}",
                    event.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Converts a GenericEvent to a simple Map structure for JSON serialization.
     * This avoids issues with nostr-java's complex object graph and null values.
     * Validates NIP-01 required fields using nostr-java's built-in validation.
     */
    private Map<String, Object> toSerializableEvent(GenericEvent event) {
        // Validate NIP-01 required fields using nostr-java's built-in validation
        try {
            event.validate();
        } catch (Exception e) {
            String eventId = event.getId() != null ? event.getId() : "unknown";
            LOGGER.warn("nip01_validation_failed event_id={} error={}", eventId, e.getMessage());
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", event.getId());
        map.put("pubkey", event.getPubKey() != null ? event.getPubKey().toString() : null);
        map.put("created_at", event.getCreatedAt());
        map.put("kind", event.getKind());
        map.put("content", event.getContent() != null ? event.getContent() : "");
        map.put("sig", event.getSignature() != null ? event.getSignature().toString() : null);

        // Convert tags to simple string arrays (NIP-01 format)
        List<List<String>> tags = new ArrayList<>();
        if (event.getTags() != null) {
            for (var tag : event.getTags()) {
                List<String> tagList = serializeTag(tag);
                if (!tagList.isEmpty()) {
                    tags.add(tagList);
                }
            }
        }
        map.put("tags", tags);

        return map;
    }

    /**
     * Serializes a BaseTag to a list of strings per NIP-01.
     * Handles GenericTag and attempts best-effort serialization for other types.
     */
    private List<String> serializeTag(nostr.event.BaseTag tag) {
        List<String> tagList = new ArrayList<>();

        if (tag instanceof nostr.event.tag.GenericTag genericTag) {
            // GenericTag: use getCode() and getAttributes()
            String code = genericTag.getCode();
            if (code != null) {
                tagList.add(code);
            }
            if (genericTag.getParams() != null) {
                for (String param : genericTag.getParams()) {
                    if (param != null) {
                        tagList.add(param);
                    }
                }
            }
        } else if (tag != null) {
            // Other BaseTag implementations: attempt reflection-based serialization
            try {
                // Try to get code via getCode() method if it exists
                var getCodeMethod = tag.getClass().getMethod("getCode");
                Object code = getCodeMethod.invoke(tag);
                if (code != null) {
                    tagList.add(code.toString());
                }

                // Try to get attributes via getAttributes() method if it exists
                var getAttrsMethod = tag.getClass().getMethod("getAttributes");
                Object attrs = getAttrsMethod.invoke(tag);
                if (attrs instanceof List<?> attrList) {
                    for (Object attr : attrList) {
                        if (attr != null) {
                            // Try to get value from attribute
                            try {
                                var valueMethod = attr.getClass().getMethod("value");
                                Object value = valueMethod.invoke(attr);
                                if (value != null) {
                                    tagList.add(value.toString());
                                }
                            } catch (NoSuchMethodException e) {
                                tagList.add(attr.toString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Fallback: log warning about unhandled tag type
                LOGGER.warn("tag_serialization_fallback tag_type={} event_id={} using_toString",
                        tag.getClass().getSimpleName(), "unknown");
                // Last resort: use toString() which may not be NIP-01 compliant
                String tagStr = tag.toString();
                if (tagStr != null && !tagStr.isEmpty()) {
                    tagList.add(tagStr);
                }
            }
        }

        return tagList;
    }

    @Override
    public Optional<StoredEvent> findByEventId(String eventId) {
        if (!available || eventId == null) {
            return Optional.empty();
        }

        try (Transaction txn = ndb.beginTransaction()) {
            return ndb.getNoteById(txn, eventId)
                    .map(this::toStoredEvent);
        } catch (Exception e) {
            LOGGER.error("event_lookup_failed event_id={} error={}",
                    eventId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<StoredEvent> findLatestByVoucherId(String voucherId) {
        if (!available || voucherId == null) {
            return Optional.empty();
        }

        String dTagValue = VOUCHER_D_TAG_PREFIX + voucherId;

        try (Transaction txn = ndb.beginTransaction();
             Filter filter = Filter.builder()
                     .kinds(VOUCHER_KIND)
                     .dTag(dTagValue)
                     .limit(1)
                     .build()) {

            List<Note> notes = ndb.queryNotes(txn, filter, 1);
            return notes.isEmpty()
                    ? Optional.empty()
                    : Optional.of(toStoredEvent(notes.get(0)));

        } catch (Exception e) {
            LOGGER.error("voucher_lookup_failed voucher_id={} error={}",
                    voucherId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<StoredEvent> findAllByVoucherId(String voucherId, int limit) {
        if (!available || voucherId == null) {
            return List.of();
        }

        String dTagValue = VOUCHER_D_TAG_PREFIX + voucherId;

        try (Transaction txn = ndb.beginTransaction();
             Filter filter = Filter.builder()
                     .kinds(VOUCHER_KIND)
                     .dTag(dTagValue)
                     .limit(limit)
                     .build()) {

            List<Note> notes = ndb.queryNotes(txn, filter, limit);
            return notes.stream()
                    .map(this::toStoredEvent)
                    .toList();

        } catch (Exception e) {
            LOGGER.error("voucher_history_lookup_failed voucher_id={} error={}",
                    voucherId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<StoredEvent> findChildrenByParentId(String parentVoucherId, int limit) {
        if (!available || parentVoucherId == null) {
            return List.of();
        }

        // Query all voucher events and filter by parent tag client-side
        // nostrdb-jni supports generic tag filtering which we use here
        try (Transaction txn = ndb.beginTransaction();
             Filter filter = Filter.builder()
                     .kinds(VOUCHER_KIND)
                     .tag("parent", parentVoucherId)
                     .limit(limit)
                     .build()) {

            List<Note> notes = ndb.queryNotes(txn, filter, limit);
            return notes.stream()
                    .map(this::toStoredEvent)
                    .toList();

        } catch (Exception e) {
            LOGGER.error("children_lookup_failed parent_id={} error={}",
                    parentVoucherId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<StoredEvent> findByAuthor(String pubkey, int limit) {
        if (!available || pubkey == null) {
            return List.of();
        }

        try (Transaction txn = ndb.beginTransaction();
             Filter filter = Filter.builder()
                     .kinds(VOUCHER_KIND)
                     .authors(pubkey)
                     .limit(limit)
                     .build()) {

            List<Note> notes = ndb.queryNotes(txn, filter, limit);
            return notes.stream()
                    .map(this::toStoredEvent)
                    .toList();

        } catch (Exception e) {
            LOGGER.error("author_lookup_failed pubkey={} error={}",
                    pubkey, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<StoredEvent> findAllVouchers(int limit) {
        if (!available) {
            return List.of();
        }

        try (Transaction txn = ndb.beginTransaction();
             Filter filter = Filter.builder()
                     .kinds(VOUCHER_KIND)
                     .limit(limit)
                     .build()) {

            List<Note> notes = ndb.queryNotes(txn, filter, limit);
            return notes.stream()
                    .map(this::toStoredEvent)
                    .toList();

        } catch (Exception e) {
            LOGGER.error("voucher_search_failed error={}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean contains(String eventId) {
        return findByEventId(eventId).isPresent();
    }

    @Override
    public boolean isAvailable() {
        return available && !closed.get();
    }

    @Override
    public StoreStatistics getStatistics() {
        if (!available) {
            return StoreStatistics.unavailable();
        }

        try (Transaction txn = ndb.beginTransaction();
             Filter filter = Filter.builder()
                     .kinds(VOUCHER_KIND)
                     .limit(MAX_QUERY_LIMIT)
                     .build()) {

            List<Note> notes = ndb.queryNotes(txn, filter, MAX_QUERY_LIMIT);
            long voucherCount = notes.size();

            // Estimate database size from directory
            long dbSize = estimateDatabaseSize();

            return new StoreStatistics(voucherCount, voucherCount, dbSize, true);

        } catch (Exception e) {
            LOGGER.error("statistics_query_failed error={}", e.getMessage());
            return StoreStatistics.unavailable();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ndb != null) {
            try {
                ndb.close();
                LOGGER.info("event_store_closed path={}", config.databasePath());
            } catch (Exception e) {
                LOGGER.error("event_store_close_failed error={}", e.getMessage());
            }
        }
    }

    private StoredEvent toStoredEvent(Note note) {
        GenericEvent event = noteToGenericEvent(note);
        String relayUrl = eventRelayMap.getOrDefault(note.id(), "cached");
        return new StoredEvent(event, relayUrl, Instant.now());
    }

    private GenericEvent noteToGenericEvent(Note note) {
        GenericEvent event = new GenericEvent();
        event.setId(note.id());
        event.setPubKey(new nostr.base.PublicKey(note.pubkey()));
        event.setCreatedAt(note.createdAt());
        event.setKind(note.kind());
        event.setContent(note.content());
        if (note.sig() != null) {
            event.setSignature(nostr.base.Signature.fromString(note.sig()));
        }

        // Convert tags
        if (note.tags() != null) {
            List<nostr.event.BaseTag> genericTags = new ArrayList<>();
            for (List<String> tagList : note.tags()) {
                if (!tagList.isEmpty()) {
                    String tagCode = tagList.get(0);
                    List<String> params = new ArrayList<>();
                    for (int i = 1; i < tagList.size(); i++) {
                        params.add(tagList.get(i));
                    }
                    genericTags.add(new nostr.event.tag.GenericTag(tagCode, params));
                }
            }
            event.setTags(genericTags);
        }

        return event;
    }

    private long estimateDatabaseSize() {
        try {
            Path dbPath = config.databasePath();
            if (Files.exists(dbPath)) {
                try (var pathStream = Files.walk(dbPath)) {
                    return pathStream
                            .filter(Files::isRegularFile)
                            .mapToLong(p -> {
                                try {
                                    return Files.size(p);
                                } catch (IOException e) {
                                    return 0;
                                }
                            })
                            .sum();
                }
            }
        } catch (IOException e) {
            LOGGER.debug("database_size_estimation_failed error={}", e.getMessage());
        }
        return 0;
    }
}
