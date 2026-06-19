package xyz.tcheeric.cashu.ledger.trace.publisher;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * Generates operation identifiers. New operations get a time-ordered UUIDv7
 * (RFC 9562); backfilled operations get a deterministic UUIDv5 derived from stable
 * operation context (design FR-19/FR-19b), so replaying a log never duplicates.
 */
public final class OperationIds {

    /** Project namespace for deterministic backfill ids. */
    public static final UUID TRACE_NAMESPACE = UUID.fromString("a3f1c0de-7ace-5b1d-9c4e-0a1b2c3d4e5f");

    private static final SecureRandom RANDOM = new SecureRandom();

    private OperationIds() {
    }

    /** A fresh time-ordered UUIDv7 using the current clock. */
    public static String uuidV7() {
        byte[] rand = new byte[10];
        RANDOM.nextBytes(rand);
        return uuidV7(System.currentTimeMillis(), rand);
    }

    /**
     * A UUIDv7 with an explicit timestamp and 10 random bytes (for deterministic
     * tests). The first 48 bits are the big-endian millisecond timestamp.
     */
    public static String uuidV7(long epochMilli, byte[] random10) {
        if (random10 == null || random10.length < 10) {
            throw new IllegalArgumentException("random10 must be at least 10 bytes");
        }
        byte[] b = new byte[16];
        b[0] = (byte) (epochMilli >>> 40);
        b[1] = (byte) (epochMilli >>> 32);
        b[2] = (byte) (epochMilli >>> 24);
        b[3] = (byte) (epochMilli >>> 16);
        b[4] = (byte) (epochMilli >>> 8);
        b[5] = (byte) epochMilli;
        System.arraycopy(random10, 0, b, 6, 10);
        b[6] = (byte) ((b[6] & 0x0f) | 0x70); // version 7
        b[8] = (byte) ((b[8] & 0x3f) | 0x80); // variant 10
        return toUuidString(b);
    }

    /**
     * A deterministic UUIDv5 (SHA-1) of {@code name} within {@link #TRACE_NAMESPACE}.
     */
    public static String uuidV5(String name) {
        return uuidV5(TRACE_NAMESPACE, name);
    }

    /** A deterministic UUIDv5 of {@code name} within {@code namespace}. */
    public static String uuidV5(UUID namespace, String name) {
        byte[] nsBytes = ByteBuffer.allocate(16)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits())
                .array();
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[nsBytes.length + nameBytes.length];
        System.arraycopy(nsBytes, 0, input, 0, nsBytes.length);
        System.arraycopy(nameBytes, 0, input, nsBytes.length, nameBytes.length);
        byte[] hash = sha1(input);
        byte[] b = new byte[16];
        System.arraycopy(hash, 0, b, 0, 16);
        b[6] = (byte) ((b[6] & 0x0f) | 0x50); // version 5
        b[8] = (byte) ((b[8] & 0x3f) | 0x80); // variant 10
        return toUuidString(b);
    }

    /**
     * Deterministic backfill id from stable operation context (FR-19b):
     * {@code mint_url | op | ISO-8601 transition_at | sorted input Ys | sorted output Ys}.
     */
    public static String backfillId(String mintUrl, String op, String isoTransitionAt,
                                    List<String> sortedInputYs, List<String> sortedOutputYs) {
        String name = String.join("|",
                mintUrl,
                op,
                isoTransitionAt,
                String.join(",", sortedInputYs),
                String.join(",", sortedOutputYs));
        return uuidV5(name);
    }

    private static String toUuidString(byte[] b) {
        ByteBuffer buf = ByteBuffer.wrap(b);
        return new UUID(buf.getLong(), buf.getLong()).toString();
    }

    private static byte[] sha1(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable in this JVM", e);
        }
    }
}
