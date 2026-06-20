package xyz.tcheeric.cashu.ledger.core.trace;

/**
 * Non-sensitive description of a registered redaction key (design §7.2). The {@code fingerprint}
 * is a non-reversible digest so operators can identify a key in listings without exposing it.
 *
 * @param keyId        the operator-chosen key identifier
 * @param label        a human-readable description
 * @param createdAtMs  when the key was registered (epoch millis)
 * @param fingerprint  a short non-reversible digest of the key material
 */
public record RedactionKeyMetadata(String keyId, String label, long createdAtMs, String fingerprint) {
}
