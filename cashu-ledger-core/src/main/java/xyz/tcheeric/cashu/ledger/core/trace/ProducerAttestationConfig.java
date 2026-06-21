package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps a mint URL to the set of producer signer pubkeys authorised to publish
 * trace events for it (design §7.1). The ledger rejects events signed by any other
 * key. NIP-26 delegation is not yet honoured.
 */
public final class ProducerAttestationConfig {

    private final Map<String, Set<String>> signersByMint;

    public ProducerAttestationConfig(Map<String, Set<String>> signersByMint) {
        // Normalise pubkeys to lowercase for comparison.
        this.signersByMint = signersByMint.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet())));
    }

    /** Whether {@code pubkey} is an authorised signer for {@code mintUrl}. */
    public boolean isAuthorised(String mintUrl, String pubkey) {
        Set<String> signers = signersByMint.get(mintUrl);
        return signers != null && signers.contains(pubkey.toLowerCase(Locale.ROOT));
    }
}
