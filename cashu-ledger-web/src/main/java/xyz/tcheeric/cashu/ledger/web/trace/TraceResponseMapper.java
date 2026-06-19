package xyz.tcheeric.cashu.ledger.web.trace;

import java.util.List;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.web.security.TraceAuthority;
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

    public EventView toView(StoredEvent stored, TracePrincipal principal) {
        TransactionEvent e = stored.event();
        PrivacyMode returned = returnedMode(e.privacyMode(), principal);
        boolean includeSecrets = returned != PrivacyMode.MINIMAL;

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
                e.issuerId().orElse(null),
                e.issuerPubkey().orElse(null),
                e.kind().isTerminalKind() ? "terminal" : "active",
                e.feeAmount().orElse(null),
                returned.wireValue(),
                e.schemaVersion());
    }

    public List<EventView> toViews(List<StoredEvent> events, TracePrincipal principal) {
        return events.stream().map(s -> toView(s, principal)).toList();
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
