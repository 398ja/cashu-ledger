package xyz.tcheeric.cashu.ledger.core.trace;

/**
 * Records voucher-status watcher outcomes (cashu-ledger#17) without coupling the core to a metrics
 * library. The web layer binds this to Micrometer; the default is a no-op.
 */
public interface VoucherStatusMetricsRecorder {

  VoucherStatusMetricsRecorder NOOP = new VoucherStatusMetricsRecorder() {};

  /** A verified, authorised status was recorded. */
  default void recordAccepted() {}

  /** A status event was dropped; {@code reasonCode} names why (e.g. {@code INVALID_SIGNATURE}). */
  default void recordRejected(String reasonCode) {}

  /** Two verified terminal statuses disagree for one voucher (possible double spend). */
  default void recordTerminalConflict() {}
}
