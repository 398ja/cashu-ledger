package xyz.tcheeric.cashu.ledger.web.trace;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.trace.TraceEventSummary;

/**
 * The voucher inspect response: the voucher fields (unwrapped at the root for backward
 * compatibility) plus the secret-free trace-event summaries bound to it, giving the
 * voucher↔token cross-link (design §5.4, FR-024) without exposing proof secrets through
 * the non-trace-gated voucher API.
 *
 * @param voucher            the inspected voucher, flattened into the response root
 * @param transactionEvents  secret-free summaries of the trace events referencing it
 */
public record VoucherInspectionView(
        @JsonUnwrapped VoucherNode voucher,
        List<TraceEventSummary> transactionEvents) {
}
