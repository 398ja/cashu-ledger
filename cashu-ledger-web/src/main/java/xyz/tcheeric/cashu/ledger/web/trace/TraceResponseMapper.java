package xyz.tcheeric.cashu.ledger.web.trace;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.ledger.core.trace.SqliteSidecarIndex;
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.web.security.TraceAuthority;
import xyz.tcheeric.cashu.ledger.web.security.TraceIssuerProperties;
import xyz.tcheeric.cashu.ledger.web.security.TraceIssuerProperties.PreVoucherExposure;
import xyz.tcheeric.cashu.ledger.web.security.TracePrincipal;

/**
 * Maps a {@link StoredEvent} to an {@link EventView} shaped to the caller's access
 * level (design §5.5 / §7.3, FR-028). A caller without {@code read:full} never
 * receives secret-bearing fields; the returned shape is the more restrictive of
 * what the caller may see and what the event actually stored.
 *
 * <p>Note: downgrading a FULL-stored event to the HASHED shape would require the
 * deployment redaction key at read time. Until that is wired, a hashed-level caller
 * receives the MINIMAL shape unless the event was itself stored HASHED — strictly
 * safe (never over-discloses), slightly less informative for hashed callers.</p>
 */
@Component
public final class TraceResponseMapper {

    private static final String PROVENANCE_EVENT_TAG = "event_tag";
    private static final String PROVENANCE_INDEX_BACKFILL = "index_backfill";

    private final SqliteSidecarIndex index;
    private final PreVoucherExposure preVoucherExposure;

    public TraceResponseMapper(SqliteSidecarIndex index, TraceIssuerProperties issuerProperties) {
        this.index = index;
        this.preVoucherExposure = issuerProperties.getPreVoucherExposure();
    }

    public EventView toView(StoredEvent stored, TracePrincipal principal) {
        TransactionEvent e = stored.event();
        PrivacyMode returned = returnedMode(e.privacyMode(), principal);
        boolean includeSecrets = returned != PrivacyMode.MINIMAL;
        IssuerExposure issuer = resolveIssuer(e, principal);
        Activity activity = resolveActivity(e);

        return new EventView(
                e.eventId().orElse(null),
                e.operationId(),
                e.kind().wireValue(),
                e.mintUrl(),
                e.unit(),
                e.transitionAt().toString(),
                e.producerPubkey(),
                e.initiatorPubkey().orElse(null),
                e.inputs().stream().map(p -> toProofView(p, includeSecrets)).toList(),
                e.outputs().stream().map(p -> toProofView(p, includeSecrets)).toList(),
                e.lightning().map(l -> toLightningView(l, includeSecrets)).orElse(null),
                e.voucherRef().orElse(null),
                issuer.issuerId(),
                issuer.issuerPubkey(),
                issuer.provenance(),
                activity.value(),
                activity.reason(),
                e.feeAmount().orElse(null),
                returned.wireValue(),
                e.schemaVersion());
    }

    public List<EventView> toViews(List<StoredEvent> events, TracePrincipal principal) {
        return events.stream().map(s -> toView(s, principal)).toList();
    }

    private IssuerExposure resolveIssuer(TransactionEvent e, TracePrincipal principal) {
        boolean backfilled = e.eventId().map(index::isIssuerBackfilled).orElse(false);
        String issuerId = e.issuerId().orElse(null);
        String issuerPubkey = e.issuerPubkey().orElse(null);
        // A back-filled issuer lives only in the sidecar; the raw event carries none.
        if (issuerId == null && issuerPubkey == null && backfilled) {
            SqliteSidecarIndex.IssuerAttribution attr =
                    e.eventId().flatMap(index::issuerOf).orElse(null);
            if (attr != null) {
                issuerId = attr.issuerId();
                issuerPubkey = attr.issuerPubkey();
            }
        }
        if (issuerId == null && issuerPubkey == null) {
            return IssuerExposure.NONE;
        }
        boolean bound = e.voucherRef().isPresent() || backfilled;
        if (!bound && withholdPreVoucherIssuer(principal)) {
            return IssuerExposure.NONE;
        }
        String provenance = backfilled ? PROVENANCE_INDEX_BACKFILL : PROVENANCE_EVENT_TAG;
        return new IssuerExposure(issuerId, issuerPubkey, provenance);
    }

    private boolean withholdPreVoucherIssuer(TracePrincipal principal) {
        if (preVoucherExposure == PreVoucherExposure.SUPPRESS) {
            return true;
        }
        boolean elevated = principal.authorities().contains(TraceAuthority.READ_HASHED)
                || principal.authorities().contains(TraceAuthority.READ_FULL);
        return !elevated;
    }

    private Activity resolveActivity(TransactionEvent e) {
        Optional<SqliteSidecarIndex.ActivityState> cached =
                e.eventId().flatMap(index::activityOf);
        if (cached.isPresent()) {
            return new Activity(cached.get().activity(), cached.get().reason().orElse(null));
        }
        boolean terminalKind = e.kind().isTerminalKind();
        return new Activity(terminalKind ? "terminal" : "active", terminalKind ? "terminal_kind" : null);
    }

    private record IssuerExposure(String issuerId, String issuerPubkey, String provenance) {
        static final IssuerExposure NONE = new IssuerExposure(null, null, null);
    }

    private record Activity(String value, String reason) {
    }

    private PrivacyMode returnedMode(PrivacyMode storedMode, TracePrincipal principal) {
        if (principal.authorities().contains(TraceAuthority.READ_FULL)) {
            return storedMode;
        }
        if (principal.authorities().contains(TraceAuthority.READ_HASHED) && storedMode == PrivacyMode.HASHED) {
            return PrivacyMode.HASHED;
        }
        return PrivacyMode.MINIMAL;
    }

    private ProofView toProofView(ProofRef p, boolean includeSecrets) {
        return new ProofView(
                p.amount(), p.keysetId(), p.y(),
                includeSecrets ? p.secret().orElse(null) : null,
                includeSecrets ? p.c().orElse(null) : null,
                includeSecrets ? p.witness().orElse(null) : null);
    }

    private LightningView toLightningView(LightningRef l, boolean includeSecrets) {
        return new LightningView(
                l.quoteId(), l.mintUrl(), l.quoteOperation().wireValue(),
                l.amount().orElse(null), l.partial(),
                includeSecrets ? l.bolt11().orElse(null) : null,
                includeSecrets ? l.paymentHash().orElse(null) : null);
    }
}
