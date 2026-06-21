package xyz.tcheeric.cashu.ledger.core.trace;

/**
 * Notified when a watched voucher first enters a terminal status. The activity cache
 * subscribes to this to invalidate cached classifications for events bound to the
 * voucher (design §5.4.1 — voucher-terminal invalidation trigger).
 */
@FunctionalInterface
public interface VoucherTerminalListener {

    /** A no-op listener for deployments that do not yet wire an activity cache. */
    VoucherTerminalListener NONE = observation -> { };

    void onVoucherTerminal(VoucherStatusObservation observation);
}
