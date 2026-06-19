package xyz.tcheeric.cashu.ledger.trace.core;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * An immutable record of one Cashu token-level operation — the unit of the
 * transaction-traceability graph. See the design document §5.1 and §5.3.
 *
 * <p>The {@code inputs} and {@code outputs} lists preserve producer-supplied proof
 * order, which is part of the canonical event; {@code outputRoles}, when non-empty,
 * align positionally with {@code outputs}. Cardinality and balance rules per kind
 * are enforced by {@link OperationInvariants}; canonical serialisation and the
 * deterministic event id are produced by {@link CanonicalJson}.</p>
 *
 * @param eventId         Nostr event id (deterministic); empty before signing
 * @param operationId     producer-supplied dashed UUIDv7 ({@code d} / {@code traceability_op})
 * @param kind            the operation kind
 * @param mintUrl         normalised mint URL (lowercase scheme+host, no trailing slash)
 * @param unit            currency unit (lowercase, e.g. {@code sat})
 * @param transitionAt    producer wall-clock, millisecond precision (ordering source of truth)
 * @param createdAt       NIP-01 second-precision time ({@code floor(transitionAt/1000)})
 * @param producerPubkey  hex pubkey of the publishing system component
 * @param initiatorPubkey optional end-user identity (not cryptographically validated)
 * @param inputs          input proof references (may be empty per kind)
 * @param outputs         output proof references (may be empty per kind)
 * @param outputRoles     per-output roles; empty or aligned 1:1 with {@code outputs}
 * @param lightning       optional Lightning quote reference
 * @param voucherRef      optional related voucher d-tag
 * @param issuerId        optional merchant identifier (denormalised)
 * @param issuerPubkey    optional merchant Schnorr pubkey
 * @param bundleId        optional UUIDv7 linking SEND and matching RECEIVE
 * @param transferId      optional UUIDv7 correlating cross-mint legs
 * @param feeAmount       optional fee in the event's unit
 * @param errorCode       optional error code (for {@code *_FAILED} kinds)
 * @param errorMessage    optional error detail
 * @param correctionOf    optional event id this event corrects
 * @param privacyMode     the serialisation policy applied to proof fields
 * @param redactionKeyId  optional identifier of the HMAC redaction key (HASHED only)
 * @param overflowPolicy  optional lossy-mode declaration (non-default producers)
 * @param schemaVersion   event schema version (currently 1)
 * @param source          metadata about the underlying signed Nostr event
 */
public record TransactionEvent(
        Optional<String> eventId,
        String operationId,
        OperationKind kind,
        String mintUrl,
        String unit,
        Instant transitionAt,
        Instant createdAt,
        String producerPubkey,
        Optional<String> initiatorPubkey,
        List<ProofRef> inputs,
        List<ProofRef> outputs,
        List<OutputRole> outputRoles,
        Optional<LightningRef> lightning,
        Optional<String> voucherRef,
        Optional<String> issuerId,
        Optional<String> issuerPubkey,
        Optional<String> bundleId,
        Optional<String> transferId,
        Optional<Long> feeAmount,
        Optional<String> errorCode,
        Optional<String> errorMessage,
        Optional<String> correctionOf,
        PrivacyMode privacyMode,
        Optional<String> redactionKeyId,
        Optional<String> overflowPolicy,
        int schemaVersion,
        NostrEventMetadata source
) {

    /** The current transaction-event schema version. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public TransactionEvent {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must be present");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must be present");
        }
        if (mintUrl == null || mintUrl.isBlank()) {
            throw new IllegalArgumentException("mintUrl must be present");
        }
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("unit must be present");
        }
        if (transitionAt == null) {
            throw new IllegalArgumentException("transitionAt must be present");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must be present");
        }
        if (producerPubkey == null || producerPubkey.isBlank()) {
            throw new IllegalArgumentException("producerPubkey must be present");
        }
        if (privacyMode == null) {
            throw new IllegalArgumentException("privacyMode must be present");
        }
        // Defensive immutable copies; null lists treated as empty.
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        outputRoles = outputRoles == null ? List.of() : List.copyOf(outputRoles);
        if (!outputRoles.isEmpty() && outputRoles.size() != outputs.size()) {
            throw new IllegalArgumentException(
                    "outputRoles, when present, must align 1:1 with outputs ("
                            + outputRoles.size() + " roles vs " + outputs.size() + " outputs)");
        }
        eventId = eventId == null ? Optional.empty() : eventId;
        initiatorPubkey = initiatorPubkey == null ? Optional.empty() : initiatorPubkey;
        lightning = lightning == null ? Optional.empty() : lightning;
        voucherRef = voucherRef == null ? Optional.empty() : voucherRef;
        issuerId = issuerId == null ? Optional.empty() : issuerId;
        issuerPubkey = issuerPubkey == null ? Optional.empty() : issuerPubkey;
        bundleId = bundleId == null ? Optional.empty() : bundleId;
        transferId = transferId == null ? Optional.empty() : transferId;
        feeAmount = feeAmount == null ? Optional.empty() : feeAmount;
        errorCode = errorCode == null ? Optional.empty() : errorCode;
        errorMessage = errorMessage == null ? Optional.empty() : errorMessage;
        correctionOf = correctionOf == null ? Optional.empty() : correctionOf;
        redactionKeyId = redactionKeyId == null ? Optional.empty() : redactionKeyId;
        overflowPolicy = overflowPolicy == null ? Optional.empty() : overflowPolicy;
    }
}
