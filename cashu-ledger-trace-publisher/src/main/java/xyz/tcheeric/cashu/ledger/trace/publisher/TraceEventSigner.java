package xyz.tcheeric.cashu.ledger.trace.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import nostr.crypto.schnorr.Schnorr;
import nostr.crypto.schnorr.SchnorrException;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Signs a {@link TransactionEvent} into a publishable Nostr event. The event id is
 * computed by {@link CanonicalJson} (our canonical NIP-01 serialisation) and the
 * Schnorr signature is taken over that id directly — bypassing any third-party
 * serialiser so the published id always equals our deterministic id (idempotency,
 * FR-18). A relay re-deriving the id from the published tags+content will arrive at
 * the same value because both follow NIP-01 canonicalisation.
 */
public final class TraceEventSigner {

    private static final HexFormat HEX = HexFormat.of();

    private final byte[] privateKey;
    private final String publicKeyHex;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    /**
     * @param privateKeyHex 32-byte secp256k1 Schnorr private key, hex-encoded
     */
    public TraceEventSigner(String privateKeyHex) {
        this.privateKey = HEX.parseHex(privateKeyHex);
        if (privateKey.length != 32) {
            throw new IllegalArgumentException("private key must be 32 bytes, was " + privateKey.length);
        }
        try {
            this.publicKeyHex = HEX.formatHex(Schnorr.genPubKey(privateKey));
        } catch (SchnorrException e) {
            throw new IllegalStateException("Failed to derive public key from private key", e);
        }
    }

    /** The x-only public key (64-char hex) that this signer produces. */
    public String publicKeyHex() {
        return publicKeyHex;
    }

    /**
     * Signs {@code event}. The event's {@code producerPubkey} must equal this
     * signer's public key.
     *
     * @throws TraceabilityPublishException if the pubkey mismatches or signing fails
     */
    public SignedTraceEvent sign(TransactionEvent event) {
        if (!publicKeyHex.equalsIgnoreCase(event.producerPubkey())) {
            throw new TraceabilityPublishException("PRODUCER_PUBKEY_MISMATCH",
                    "Event producerPubkey " + event.producerPubkey()
                            + " does not match signer key " + publicKeyHex);
        }
        String eventId = CanonicalJson.eventId(event);
        List<List<String>> tags = CanonicalJson.tags(event);
        String content = CanonicalJson.content(event);

        byte[] signature;
        try {
            byte[] auxRand = new byte[32];
            random.nextBytes(auxRand);
            signature = Schnorr.sign(HEX.parseHex(eventId), privateKey, auxRand);
        } catch (SchnorrException e) {
            throw new TraceabilityPublishException("SIGNING_FAILED",
                    "Failed to Schnorr-sign event " + eventId, e);
        }
        String signatureHex = HEX.formatHex(signature);
        return new SignedTraceEvent(eventId, signatureHex,
                assemble(eventId, event, tags, content, signatureHex));
    }

    private String assemble(String eventId, TransactionEvent event,
                            List<List<String>> tags, String content, String signatureHex) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", eventId);
        root.put("pubkey", publicKeyHex);
        root.put("created_at", event.createdAt().getEpochSecond());
        root.put("kind", NostrEventMetadata.TRACE_EVENT_KIND);
        ArrayNode tagsNode = root.putArray("tags");
        for (List<String> tag : tags) {
            ArrayNode tagNode = tagsNode.addArray();
            tag.forEach(tagNode::add);
        }
        root.put("content", content);
        root.put("sig", signatureHex);
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new TraceabilityPublishException("SERIALISATION_FAILED",
                    "Failed to serialise signed event " + eventId, e);
        }
    }
}
