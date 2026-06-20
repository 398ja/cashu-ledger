package xyz.tcheeric.cashu.ledger.core.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Re-redacts an event for off-system sharing (design §7.2 — sanitised export). Secret-bearing
 * fields are re-keyed under a fresh ephemeral HMAC key — never the deployment's production
 * redaction key — so the exported HMACs cannot be correlated with the live ledger, the
 * bundleToken is stripped, and the event is tagged {@code sanitised:true}. The ephemeral key
 * is generated per export and discarded.
 */
public final class TraceSanitiser {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String[] PROOF_SECRET_FIELDS = {"secret", "c", "witness"};
    private static final String[] LIGHTNING_SECRET_FIELDS = {"bolt11", "paymentHash"};
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] ephemeralKey;

    public TraceSanitiser(byte[] ephemeralKey) {
        this.ephemeralKey = ephemeralKey.clone();
    }

    /** A sanitiser with a fresh 256-bit ephemeral key. */
    public static TraceSanitiser withEphemeralKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return new TraceSanitiser(key);
    }

    /**
     * Refuses to sanitise with the production redaction key, which would leak correlatable
     * HMACs into the export.
     */
    public void assertNotProductionKey(byte[] productionKey) {
        if (productionKey != null && MessageDigest.isEqual(ephemeralKey, productionKey)) {
            throw new IllegalArgumentException(
                    "refusing to sanitise with the production redaction key; use an ephemeral key");
        }
    }

    /** Returns a sanitised copy of an event view: secrets re-keyed, bundleToken stripped. */
    public JsonNode sanitise(JsonNode event) {
        ObjectNode copy = event.deepCopy();
        reKeyProofs(copy.get("inputs"));
        reKeyProofs(copy.get("outputs"));
        reKeyFields(copy.get("lightning"), LIGHTNING_SECRET_FIELDS);
        copy.remove("bundleToken");
        copy.put("sanitised", true);
        return copy;
    }

    private void reKeyProofs(JsonNode proofs) {
        if (proofs == null || !proofs.isArray()) {
            return;
        }
        for (JsonNode proof : proofs) {
            reKeyFields(proof, PROOF_SECRET_FIELDS);
        }
    }

    private void reKeyFields(JsonNode node, String[] fields) {
        if (node == null || !node.isObject()) {
            return;
        }
        ObjectNode object = (ObjectNode) node;
        for (String field : fields) {
            JsonNode value = object.get(field);
            if (value != null && value.isTextual()) {
                object.put(field, hmacHex(value.asText()));
            }
        }
    }

    private String hmacHex(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(ephemeralKey, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute sanitising HMAC", e);
        }
    }
}
