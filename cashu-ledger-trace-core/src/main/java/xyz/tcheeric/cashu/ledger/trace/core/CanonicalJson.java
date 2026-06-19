package xyz.tcheeric.cashu.ledger.trace.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HexFormat;

/**
 * Produces the deterministic, canonical wire form of a {@link TransactionEvent}:
 * the canonical {@code content} JSON, the ordered tag list, and the resulting
 * Nostr event id. Determinism is what makes idempotency real (design §4.5 / §5.3):
 * the same logical operation always yields the same event id.
 *
 * <p>Canonical JSON follows RFC 8785 (JCS) for the value shapes used here: object
 * keys sorted by UTF-16 code unit, arrays in element order, integers without
 * exponent, and JSON string escaping limited to {@code "}, {@code \}, and control
 * characters below {@code U+0020}. The event id is the SHA-256 over the NIP-01
 * serialisation array {@code [0, pubkey, created_at, kind, tags, content]}.</p>
 *
 * <p>This serialiser is pure: it serialises whatever proof fields the event
 * carries. Producers MUST apply {@link ProofRefRedactor} for the event's
 * {@link PrivacyMode} <em>before</em> canonicalising.</p>
 */
public final class CanonicalJson {

    private static final HexFormat HEX = HexFormat.of();

    private CanonicalJson() {
    }

    // ---- content -----------------------------------------------------------

    /**
     * The canonical {@code content} JSON for an event. {@code inputs} and
     * {@code outputs} are always present (possibly empty); {@code lightning} and
     * {@code error_message} are omitted when absent (never {@code null}).
     */
    public static String content(TransactionEvent event) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("inputs", proofArray(event.inputs()));
        root.put("outputs", proofArray(event.outputs()));
        event.lightning().ifPresent(ln -> root.put("lightning", lightningObject(ln)));
        event.errorMessage().ifPresent(msg -> root.put("error_message", msg));
        return write(root);
    }

    private static List<Object> proofArray(List<ProofRef> proofs) {
        List<Object> array = new ArrayList<>(proofs.size());
        for (ProofRef proof : proofs) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("amount", proof.amount());
            obj.put("id", proof.keysetId());
            obj.put("y", proof.y());
            proof.secret().ifPresent(s -> obj.put("secret", s));
            proof.c().ifPresent(c -> obj.put("C", c));
            proof.witness().ifPresent(w -> obj.put("witness", w));
            proof.dleq().ifPresent(d -> obj.put("dleq", dleqObject(d)));
            array.add(obj);
        }
        return array;
    }

    private static Map<String, Object> dleqObject(DleqProof dleq) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("e", dleq.e());
        obj.put("s", dleq.s());
        dleq.r().ifPresent(r -> obj.put("r", r));
        return obj;
    }

    private static Map<String, Object> lightningObject(LightningRef ln) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("quoteId", ln.quoteId());
        obj.put("mintUrl", ln.mintUrl());
        obj.put("quoteOperation", ln.quoteOperation().wireValue());
        obj.put("partial", ln.partial());
        ln.bolt11().ifPresent(b -> obj.put("bolt11", b));
        ln.paymentHash().ifPresent(p -> obj.put("paymentHash", p));
        ln.amount().ifPresent(a -> obj.put("amount", a));
        ln.expiresAt().ifPresent(t -> obj.put("expiresAt", t.getEpochSecond()));
        return obj;
    }

    // ---- tags --------------------------------------------------------------

    /**
     * The ordered tag list in the canonical order required for a deterministic
     * event id (design §5.3). Optional tags are omitted when absent; {@code input_y}
     * / {@code output_y} / {@code output_role} follow in array-index order.
     */
    public static List<List<String>> tags(TransactionEvent e) {
        List<List<String>> tags = new ArrayList<>();
        tags.add(tag("d", e.operationId()));
        tags.add(tag("op", e.kind().wireValue()));
        tags.add(tag("mint_url", e.mintUrl()));
        tags.add(tag("unit", e.unit()));
        tags.add(tag("transition_at", Long.toString(e.transitionAt().toEpochMilli())));
        tags.add(tag("producer_pubkey", e.producerPubkey()));
        e.initiatorPubkey().ifPresent(v -> tags.add(tag("initiator_pubkey", v)));
        tags.add(tag("schema_version", Integer.toString(e.schemaVersion())));
        tags.add(tag("privacy_mode", e.privacyMode().wireValue()));
        e.redactionKeyId().ifPresent(v -> tags.add(tag("redaction_key_id", v)));
        tags.add(tag("traceability_op", e.operationId()));
        e.bundleId().ifPresent(v -> tags.add(tag("bundle_id", v)));
        e.transferId().ifPresent(v -> tags.add(tag("transfer_id", v)));
        e.voucherRef().ifPresent(v -> tags.add(tag("voucher_ref", v)));
        e.issuerId().ifPresent(v -> tags.add(tag("issuer_id", v)));
        e.issuerPubkey().ifPresent(v -> tags.add(tag("issuer_pubkey", v)));
        e.lightning().ifPresent(ln -> tags.add(tag("quote_id", e.mintUrl() + "::" + ln.quoteId())));
        e.lightning().flatMap(LightningRef::paymentHash)
                .ifPresent(v -> tags.add(tag("payment_hash", v)));
        e.feeAmount().ifPresent(v -> tags.add(tag("fee", Long.toString(v))));
        e.errorCode().ifPresent(v -> tags.add(tag("error_code", v)));
        e.correctionOf().ifPresent(v -> tags.add(tag("correction_of", v)));
        e.overflowPolicy().ifPresent(v -> tags.add(tag("overflow_policy", v)));
        for (ProofRef in : e.inputs()) {
            tags.add(tag("input_y", in.keysetId() + ":" + in.y()));
        }
        for (ProofRef out : e.outputs()) {
            tags.add(tag("output_y", out.keysetId() + ":" + out.y()));
        }
        for (OutputRole role : e.outputRoles()) {
            tags.add(tag("output_role", role.wireValue()));
        }
        return tags;
    }

    private static List<String> tag(String name, String value) {
        return List.of(name, value);
    }

    // ---- event id ----------------------------------------------------------

    /**
     * The deterministic Nostr event id for an event: SHA-256 (lowercase hex) over
     * the NIP-01 serialisation {@code [0, pubkey, created_at, kind, tags, content]}.
     * {@code created_at} is taken from {@link TransactionEvent#createdAt()} in
     * seconds (FR-18 requires it equal {@code floor(transition_at_ms / 1000)}).
     */
    public static String eventId(TransactionEvent event) {
        return eventId(
                event.producerPubkey(),
                event.createdAt().getEpochSecond(),
                tags(event),
                content(event));
    }

    /** Computes the Nostr event id from already-prepared components. */
    public static String eventId(String pubkey, long createdAtSeconds,
                                 List<List<String>> tags, String content) {
        List<Object> serial = new ArrayList<>(6);
        serial.add(0L);
        serial.add(pubkey);
        serial.add(createdAtSeconds);
        serial.add((long) NostrEventMetadata.TRACE_EVENT_KIND);
        serial.add(new ArrayList<Object>(tags));
        serial.add(content);
        byte[] bytes = write(serial).getBytes(StandardCharsets.UTF_8);
        return HEX.formatHex(sha256(bytes));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }

    // ---- canonical writer --------------------------------------------------

    /** Serialises a canonical value tree ({@link Map}, {@link List}, String, Long, Integer, Boolean). */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b.booleanValue() ? "true" : "false");
            case Integer i -> sb.append(i.intValue());
            case Long l -> sb.append(l.longValue());
            case Map<?, ?> map -> writeObject(sb, (Map<String, Object>) map);
            case List<?> list -> writeArray(sb, list);
            default -> throw new IllegalArgumentException(
                    "unsupported canonical value type: " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map) {
        // Sort keys by UTF-16 code unit order (RFC 8785 / NIP-01).
        Map<String, Object> sorted = new TreeMap<>(map);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, entry.getKey());
            sb.append(':');
            writeValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
