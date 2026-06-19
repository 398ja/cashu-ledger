package xyz.tcheeric.cashu.ledger.trace.core;

import java.time.Instant;
import java.util.Optional;

/**
 * A composite filter for listing transaction events. All fields are optional; an
 * unset field applies no constraint. Built via {@link #builder()}.
 *
 * @param mintUrl         restrict to a mint
 * @param producerPubkey  restrict to a producer
 * @param initiatorPubkey restrict to an initiating user
 * @param issuerId        restrict to a merchant identifier
 * @param issuerPubkey    restrict to a merchant pubkey
 * @param voucherRef      restrict to a voucher
 * @param quoteId         restrict to a Lightning quote (composite {@code mintUrl::quoteId})
 * @param transferId      restrict to a cross-mint transfer id
 * @param bundleId        restrict to a SEND/RECEIVE bundle id
 * @param kind            restrict to an operation kind
 * @param since           lower bound on {@code transition_at} (inclusive)
 * @param until           upper bound on {@code transition_at} (exclusive)
 * @param activity        restrict to ACTIVE or TERMINAL events
 * @param limit           maximum results
 * @param cursor          opaque pagination cursor from a prior page
 */
public record TraceEventQuery(
        Optional<String> mintUrl,
        Optional<String> producerPubkey,
        Optional<String> initiatorPubkey,
        Optional<String> issuerId,
        Optional<String> issuerPubkey,
        Optional<String> voucherRef,
        Optional<String> quoteId,
        Optional<String> transferId,
        Optional<String> bundleId,
        Optional<OperationKind> kind,
        Optional<Instant> since,
        Optional<Instant> until,
        Optional<EventActivity> activity,
        int limit,
        Optional<String> cursor
) {

    /** Default page size when a caller does not specify one. */
    public static final int DEFAULT_LIMIT = 100;

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for {@link TraceEventQuery}; unset fields apply no filter. */
    public static final class Builder {
        private String mintUrl;
        private String producerPubkey;
        private String initiatorPubkey;
        private String issuerId;
        private String issuerPubkey;
        private String voucherRef;
        private String quoteId;
        private String transferId;
        private String bundleId;
        private OperationKind kind;
        private Instant since;
        private Instant until;
        private EventActivity activity;
        private int limit = DEFAULT_LIMIT;
        private String cursor;

        public Builder mintUrl(String v) { this.mintUrl = v; return this; }
        public Builder producerPubkey(String v) { this.producerPubkey = v; return this; }
        public Builder initiatorPubkey(String v) { this.initiatorPubkey = v; return this; }
        public Builder issuerId(String v) { this.issuerId = v; return this; }
        public Builder issuerPubkey(String v) { this.issuerPubkey = v; return this; }
        public Builder voucherRef(String v) { this.voucherRef = v; return this; }
        public Builder quoteId(String v) { this.quoteId = v; return this; }
        public Builder transferId(String v) { this.transferId = v; return this; }
        public Builder bundleId(String v) { this.bundleId = v; return this; }
        public Builder kind(OperationKind v) { this.kind = v; return this; }
        public Builder since(Instant v) { this.since = v; return this; }
        public Builder until(Instant v) { this.until = v; return this; }
        public Builder activity(EventActivity v) { this.activity = v; return this; }
        public Builder limit(int v) { this.limit = v; return this; }
        public Builder cursor(String v) { this.cursor = v; return this; }

        public TraceEventQuery build() {
            return new TraceEventQuery(
                    Optional.ofNullable(mintUrl),
                    Optional.ofNullable(producerPubkey),
                    Optional.ofNullable(initiatorPubkey),
                    Optional.ofNullable(issuerId),
                    Optional.ofNullable(issuerPubkey),
                    Optional.ofNullable(voucherRef),
                    Optional.ofNullable(quoteId),
                    Optional.ofNullable(transferId),
                    Optional.ofNullable(bundleId),
                    Optional.ofNullable(kind),
                    Optional.ofNullable(since),
                    Optional.ofNullable(until),
                    Optional.ofNullable(activity),
                    limit <= 0 ? DEFAULT_LIMIT : limit,
                    Optional.ofNullable(cursor));
        }
    }

    public TraceEventQuery {
        mintUrl = mintUrl == null ? Optional.empty() : mintUrl;
        producerPubkey = producerPubkey == null ? Optional.empty() : producerPubkey;
        initiatorPubkey = initiatorPubkey == null ? Optional.empty() : initiatorPubkey;
        issuerId = issuerId == null ? Optional.empty() : issuerId;
        issuerPubkey = issuerPubkey == null ? Optional.empty() : issuerPubkey;
        voucherRef = voucherRef == null ? Optional.empty() : voucherRef;
        quoteId = quoteId == null ? Optional.empty() : quoteId;
        transferId = transferId == null ? Optional.empty() : transferId;
        bundleId = bundleId == null ? Optional.empty() : bundleId;
        kind = kind == null ? Optional.empty() : kind;
        since = since == null ? Optional.empty() : since;
        until = until == null ? Optional.empty() : until;
        activity = activity == null ? Optional.empty() : activity;
        cursor = cursor == null ? Optional.empty() : cursor;
    }
}
