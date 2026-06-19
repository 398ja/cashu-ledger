package xyz.tcheeric.cashu.ledger.web.security;

/** Thrown when a NIP-98 {@code Authorization: Nostr ...} header fails validation. */
public class Nip98Exception extends RuntimeException {
    public Nip98Exception(String message) {
        super(message);
    }
}
