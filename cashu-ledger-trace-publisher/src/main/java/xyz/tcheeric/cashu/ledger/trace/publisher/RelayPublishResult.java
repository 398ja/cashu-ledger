package xyz.tcheeric.cashu.ledger.trace.publisher;

/**
 * Outcome of a relay delivery attempt.
 *
 * @param deliveredToLedgerRelay whether at least one ledger relay returned OK:true
 * @param detail                 human-readable detail (relay reasons, errors)
 */
public record RelayPublishResult(boolean deliveredToLedgerRelay, String detail) {

    public static RelayPublishResult delivered() {
        return new RelayPublishResult(true, "ok");
    }

    public static RelayPublishResult failed(String detail) {
        return new RelayPublishResult(false, detail);
    }
}
