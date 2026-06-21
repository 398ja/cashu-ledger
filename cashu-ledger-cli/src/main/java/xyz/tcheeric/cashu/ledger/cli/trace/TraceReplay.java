package xyz.tcheeric.cashu.ledger.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.OperationIds;
import xyz.tcheeric.cashu.ledger.trace.publisher.SignedTraceEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceEventSigner;

/**
 * Converts a logged operation into a signed kind-9079 event for backfill/replay (design
 * FR-18/FR-19b). The operation id is derived deterministically from stable context via
 * {@link OperationIds#backfillId}, so replaying the same log never produces duplicates — the
 * ledger dedups on the resulting event id. The raw operation log is the producer's; this only
 * reconstructs and signs the canonical event.
 */
public final class TraceReplay {

    private final TraceEventSigner signer;

    public TraceReplay(TraceEventSigner signer) {
        this.signer = signer;
    }

    /** Builds and signs the canonical event for one logged operation. */
    public SignedTraceEvent toSignedEvent(JsonNode operation) {
        String mintUrl = required(operation, "mintUrl");
        OperationKind kind = OperationKind.fromWire(required(operation, "op"));
        String unit = operation.path("unit").asText("sat");
        Instant transitionAt = Instant.parse(required(operation, "transitionAt"));
        String producerPubkey = required(operation, "producerPubkey");
        List<ProofRef> inputs = proofs(operation.path("inputs"));
        List<ProofRef> outputs = proofs(operation.path("outputs"));

        String operationId = OperationIds.backfillId(mintUrl, kind.wireValue(),
                transitionAt.toString(), sortedYs(inputs), sortedYs(outputs));

        TransactionEvent event = new TransactionEvent(
                Optional.empty(), operationId, kind, mintUrl, unit,
                transitionAt, Instant.ofEpochSecond(transitionAt.getEpochSecond()), producerPubkey,
                Optional.empty(), inputs, outputs, List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                PrivacyMode.FULL, Optional.empty(), Optional.empty(),
                TransactionEvent.CURRENT_SCHEMA_VERSION,
                new NostrEventMetadata(Optional.empty(), 9079, Optional.empty(), Optional.empty(),
                        Instant.ofEpochSecond(transitionAt.getEpochSecond())));

        return signer.sign(event);
    }

    private static List<ProofRef> proofs(JsonNode array) {
        List<ProofRef> proofs = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode p : array) {
                proofs.add(new ProofRef(
                        p.path("amount").asLong(),
                        required(p, "keysetId"),
                        required(p, "y"),
                        optional(p, "secret"),
                        optional(p, "c"),
                        optional(p, "witness"),
                        Optional.empty()));
            }
        }
        return proofs;
    }

    private static List<String> sortedYs(List<ProofRef> proofs) {
        return proofs.stream().map(ProofRef::y).sorted().toList();
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("replay operation is missing required field '" + field + "'");
        }
        return value.asText();
    }

    private static Optional<String> optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? Optional.empty() : Optional.of(value.asText());
    }
}
