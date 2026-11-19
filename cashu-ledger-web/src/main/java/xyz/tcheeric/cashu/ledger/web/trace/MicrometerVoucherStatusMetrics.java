package xyz.tcheeric.cashu.ledger.web.trace;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.ledger.core.trace.VoucherStatusMetricsRecorder;

/**
 * Micrometer-backed {@link VoucherStatusMetricsRecorder} (cashu-ledger#17): accepted voucher-status
 * events, rejections tagged by reason, and terminal-status conflicts (possible double spends).
 */
public final class MicrometerVoucherStatusMetrics implements VoucherStatusMetricsRecorder {

  private final MeterRegistry registry;
  private final Counter accepted;
  private final Counter conflicts;

  public MicrometerVoucherStatusMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.accepted =
        Counter.builder("cashu_voucher_status_accepted_total")
            .description("Verified voucher-status events recorded")
            .register(registry);
    this.conflicts =
        Counter.builder("cashu_voucher_terminal_conflicts_total")
            .description("Conflicting verified terminal voucher statuses (possible double spend)")
            .register(registry);
  }

  @Override
  public void recordAccepted() {
    accepted.increment();
  }

  @Override
  public void recordRejected(String reasonCode) {
    registry.counter("cashu_voucher_status_rejected_total", "reason", reasonCode).increment();
  }

  @Override
  public void recordTerminalConflict() {
    conflicts.increment();
  }
}
