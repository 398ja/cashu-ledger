package xyz.tcheeric.cashu.ledger.web.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pre-voucher issuer-exposure configuration (design §5.3.1). Before an event is bound to
 * a voucher, exposing its issuer can leak merchant→activity correlation earlier than the
 * voucher ledger itself would; this control governs what read callers see in that window.
 * Once an event is voucher-bound the §5.2 exemption applies and issuer fields are returned
 * to all read authorities.
 */
@Data
@ConfigurationProperties(prefix = "trace.issuer")
public class TraceIssuerProperties {

    /** Exposure mode for issuer fields on not-yet-bound events. */
    private PreVoucherExposure preVoucherExposure = PreVoucherExposure.SUMMARY_WITHHOLD;

    /** How issuer fields are exposed on events that carry no voucher binding yet. */
    public enum PreVoucherExposure {
        /** Withhold issuer fields from every reader until the event binds to a voucher. */
        SUPPRESS,
        /** Return issuer fields to hashed/full readers; withhold from summary-only readers. */
        SUMMARY_WITHHOLD
    }
}
