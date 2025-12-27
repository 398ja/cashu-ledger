package xyz.tcheeric.cashu.ledger.core.model;

/**
 * Voucher lifecycle status with terminal indicator and ordering.
 */
public enum VoucherStatus {
    ISSUED(false, 0),
    CLAIMED(false, 1),
    SPLIT(true, 2),
    REDEEMED(true, 3),
    RECLAIMED(true, 3),
    REVOKED(true, 3),
    EXPIRED(true, 3),
    UNKNOWN(false, -1);

    private final boolean terminal;
    private final int order;

    VoucherStatus(boolean terminal, int order) {
        this.terminal = terminal;
        this.order = order;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public int order() {
        return order;
    }

    public static VoucherStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return VoucherStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
