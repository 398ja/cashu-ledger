package xyz.tcheeric.cashu.ledger.core.storage;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for the event store.
 *
 * @param databasePath path to the database directory
 * @param maxSizeBytes maximum database size in bytes
 * @param eventTtl     time-to-live for cached events
 * @param readOnly     whether the store is read-only
 */
public record EventStoreConfig(
        Path databasePath,
        long maxSizeBytes,
        Duration eventTtl,
        boolean readOnly
) {

    private static final long DEFAULT_MAX_SIZE_BYTES = 512L * 1024 * 1024; // 512MB
    private static final Duration DEFAULT_EVENT_TTL = Duration.ofDays(30);

    /**
     * Creates a configuration with default values.
     *
     * @return default configuration
     */
    public static EventStoreConfig defaults() {
        Path home = Path.of(System.getProperty("user.home"));
        return new EventStoreConfig(
                home.resolve(".cashu-ledger").resolve("ndb"),
                DEFAULT_MAX_SIZE_BYTES,
                DEFAULT_EVENT_TTL,
                false
        );
    }

    /**
     * Creates a configuration for the specified database path with defaults for other values.
     *
     * @param databasePath path to the database directory
     * @return configuration with specified path
     */
    public static EventStoreConfig withPath(Path databasePath) {
        return new EventStoreConfig(
                databasePath,
                DEFAULT_MAX_SIZE_BYTES,
                DEFAULT_EVENT_TTL,
                false
        );
    }

    /**
     * Creates a read-only configuration for the specified database path.
     *
     * @param databasePath path to the database directory
     * @return read-only configuration
     */
    public static EventStoreConfig readOnly(Path databasePath) {
        return new EventStoreConfig(
                databasePath,
                DEFAULT_MAX_SIZE_BYTES,
                DEFAULT_EVENT_TTL,
                true
        );
    }
}
