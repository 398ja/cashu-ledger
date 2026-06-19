package xyz.tcheeric.cashu.ledger.core.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.tcheeric.cashu.ledger.trace.core.DleqProof;
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.OutputRole;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Parses a signed kind-9079 Nostr event JSON into a {@link TransactionEvent} — the
 * inverse of {@code CanonicalJson}. Scalar fields come from tags; proofs, lightning,
 * and error message come from the {@code content} object (the authoritative payload).
 * Round-trips with the producer SDK's signer.
 */
public final class TraceEventMapper {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Parses {@code json}, recording the optional originating relay URL in metadata. */
    public ParsedTraceEvent parse(String json, String relayUrl) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new TraceParseException("Malformed event JSON", e);
        }
        requireFields(root, "id", "pubkey", "created_at", "kind", "tags", "content", "sig");
        int kind = root.get("kind").asInt();
        if (kind != NostrEventMetadata.TRACE_EVENT_KIND) {
            throw new TraceParseException("Unexpected kind " + kind + "; expected "
                    + NostrEventMetadata.TRACE_EVENT_KIND);
        }
        String eventId = root.get("id").asText();
        String signature = root.get("sig").asText();
        long createdAtSeconds = root.get("created_at").asLong();
        Instant createdAt = Instant.ofEpochSecond(createdAtSeconds);

        Map<String, String> scalar = new HashMap<>();
        List<OutputRole> outputRoles = new ArrayList<>();
        for (JsonNode tag : root.get("tags")) {
            if (!tag.isArray() || tag.isEmpty()) {
                continue;
            }
            String name = tag.get(0).asText();
            String value = tag.size() > 1 ? tag.get(1).asText() : "";
            if ("output_role".equals(name)) {
                outputRoles.add(OutputRole.fromWire(value));
            } else {
                scalar.putIfAbsent(name, value);
            }
        }

        JsonNode content = readContent(root.get("content").asText());
        List<ProofRef> inputs = parseProofs(content.get("inputs"));
        List<ProofRef> outputs = parseProofs(content.get("outputs"));
        Optional<LightningRef> lightning = parseLightning(content.get("lightning"));
        Optional<String> errorMessage = optionalText(content, "error_message");

        TransactionEvent event = new TransactionEvent(
                Optional.of(eventId),
                required(scalar, "d"),
                OperationKind.fromWire(required(scalar, "op")),
                required(scalar, "mint_url"),
                required(scalar, "unit"),
                Instant.ofEpochMilli(Long.parseLong(required(scalar, "transition_at"))),
                createdAt,
                required(scalar, "producer_pubkey"),
                Optional.ofNullable(scalar.get("initiator_pubkey")),
                inputs,
                outputs,
                outputRoles,
                lightning,
                Optional.ofNullable(scalar.get("voucher_ref")),
                Optional.ofNullable(scalar.get("issuer_id")),
                Optional.ofNullable(scalar.get("issuer_pubkey")),
                Optional.ofNullable(scalar.get("bundle_id")),
                Optional.ofNullable(scalar.get("transfer_id")),
                Optional.ofNullable(scalar.get("fee")).map(Long::parseLong),
                Optional.ofNullable(scalar.get("error_code")),
                errorMessage,
                Optional.ofNullable(scalar.get("correction_of")),
                PrivacyMode.fromWire(required(scalar, "privacy_mode")),
                Optional.ofNullable(scalar.get("redaction_key_id")),
                Optional.ofNullable(scalar.get("overflow_policy")),
                Integer.parseInt(required(scalar, "schema_version")),
                new NostrEventMetadata(Optional.of(eventId), kind,
                        Optional.ofNullable(relayUrl), Optional.of(signature), createdAt));

        return new ParsedTraceEvent(event, signature, json);
    }

    private JsonNode readContent(String contentString) {
        try {
            return mapper.readTree(contentString);
        } catch (Exception e) {
            throw new TraceParseException("Malformed content JSON", e);
        }
    }

    private List<ProofRef> parseProofs(JsonNode array) {
        if (array == null || !array.isArray()) {
            throw new TraceParseException("content must contain an inputs/outputs array");
        }
        List<ProofRef> proofs = new ArrayList<>();
        for (JsonNode node : array) {
            proofs.add(new ProofRef(
                    node.get("amount").asLong(),
                    node.get("id").asText(),
                    node.get("y").asText(),
                    optionalText(node, "secret"),
                    optionalText(node, "C"),
                    optionalText(node, "witness"),
                    parseDleq(node.get("dleq"))));
        }
        return proofs;
    }

    private Optional<DleqProof> parseDleq(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(new DleqProof(
                node.get("e").asText(), node.get("s").asText(), optionalText(node, "r")));
    }

    private Optional<LightningRef> parseLightning(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(new LightningRef(
                node.get("quoteId").asText(),
                node.get("mintUrl").asText(),
                optionalText(node, "bolt11"),
                optionalText(node, "paymentHash"),
                node.has("amount") ? Optional.of(node.get("amount").asLong()) : Optional.empty(),
                node.has("expiresAt")
                        ? Optional.of(Instant.ofEpochSecond(node.get("expiresAt").asLong())) : Optional.empty(),
                OperationKind.fromWire(node.get("quoteOperation").asText()),
                node.has("partial") && node.get("partial").asBoolean()));
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull()
                ? Optional.of(node.get(field).asText()) : Optional.empty();
    }

    private static String required(Map<String, String> scalar, String tag) {
        String value = scalar.get(tag);
        if (value == null) {
            throw new TraceParseException("Missing required tag: " + tag);
        }
        return value;
    }

    private static void requireFields(JsonNode root, String... fields) {
        for (String field : fields) {
            if (root.get(field) == null) {
                throw new TraceParseException("Missing required event field: " + field);
            }
        }
    }
}
