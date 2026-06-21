package xyz.tcheeric.cashu.ledger.core.trace;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM authenticated encryption used to protect redaction keys at rest (design §7.2).
 * Each ciphertext is {@code nonce(12) || tag+ct}; the 96-bit nonce is random per message and
 * prepended, so the same plaintext encrypts differently each time and tampering is detected
 * on decrypt.
 */
public final class AesGcm {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    public static byte[] encrypt(byte[] key, byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new TraceStorageException("Failed to encrypt redaction key", e);
        }
    }

    public static byte[] decrypt(byte[] key, byte[] ciphertext) {
        try {
            if (ciphertext.length <= NONCE_BYTES) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(ciphertext, 0, nonce, 0, NONCE_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(ciphertext, NONCE_BYTES, ciphertext.length - NONCE_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new TraceStorageException("Failed to decrypt redaction key", e);
        }
    }
}
