package xyz.tcheeric.cashu.ledger.core.storage;

/**
 * Statistics about the event store.
 *
 * @param totalEvents     total number of events stored
 * @param voucherEvents   number of voucher events (kind 30078)
 * @param databaseSizeBytes current database size in bytes
 * @param available       whether the store is available
 */
public record StoreStatistics(
        long totalEvents,
        long voucherEvents,
        long databaseSizeBytes,
        boolean available
) {

    /**
     * Creates statistics for an unavailable store.
     *
     * @return unavailable statistics
     */
    public static StoreStatistics unavailable() {
        return new StoreStatistics(0, 0, 0, false);
    }

    /**
     * Creates statistics for an empty but available store.
     *
     * @return empty statistics
     */
    public static StoreStatistics empty() {
        return new StoreStatistics(0, 0, 0, true);
    }
}
