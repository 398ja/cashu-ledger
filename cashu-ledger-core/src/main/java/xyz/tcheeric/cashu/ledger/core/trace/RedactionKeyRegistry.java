package xyz.tcheeric.cashu.ledger.core.trace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Registry of deployment redaction keys (design §7.2). Keys are held only as AES-GCM
 * ciphertext under a master key-encryption key, so the raw material is never stored in the
 * clear; listings expose metadata and a non-reversible fingerprint only. {@code verify}
 * confirms an operator-supplied key matches a registered one without revealing it.
 */
public final class RedactionKeyRegistry {

    private record Entry(RedactionKeyMetadata metadata, byte[] ciphertext) {
    }

    private final byte[] masterKey;
    private final LongSupplier clockMillis;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public RedactionKeyRegistry(byte[] masterKey) {
        this(masterKey, System::currentTimeMillis);
    }

    public RedactionKeyRegistry(byte[] masterKey, LongSupplier clockMillis) {
        this.masterKey = masterKey.clone();
        this.clockMillis = clockMillis;
    }

    /** Registers a redaction key, storing only its ciphertext. Fails if the id already exists. */
    public RedactionKeyMetadata register(String keyId, String label, byte[] rawKey) {
        RedactionKeyMetadata metadata = new RedactionKeyMetadata(
                keyId, label, clockMillis.getAsLong(), fingerprint(rawKey));
        Entry previous = entries.putIfAbsent(keyId,
                new Entry(metadata, AesGcm.encrypt(masterKey, rawKey)));
        if (previous != null) {
            throw new IllegalArgumentException("redaction key '" + keyId + "' is already registered");
        }
        return metadata;
    }

    /** All registered key metadata (never the raw keys), in registration order by id. */
    public List<RedactionKeyMetadata> list() {
        List<RedactionKeyMetadata> all = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            all.add(entry.metadata());
        }
        all.sort((a, b) -> a.keyId().compareTo(b.keyId()));
        return all;
    }

    /** Whether {@code candidate} matches the registered key, compared in constant time. */
    public boolean verify(String keyId, byte[] candidate) {
        Entry entry = entries.get(keyId);
        if (entry == null) {
            return false;
        }
        byte[] stored = AesGcm.decrypt(masterKey, entry.ciphertext());
        return MessageDigest.isEqual(stored, candidate);
    }

    /** Resolves the raw key material for internal redaction, if registered. */
    public Optional<byte[]> resolve(String keyId) {
        Entry entry = entries.get(keyId);
        return entry == null ? Optional.empty()
                : Optional.of(AesGcm.decrypt(masterKey, entry.ciphertext()));
    }

    private static String fingerprint(byte[] rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(concat("redaction-key-fingerprint:".getBytes(StandardCharsets.UTF_8), rawKey));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
