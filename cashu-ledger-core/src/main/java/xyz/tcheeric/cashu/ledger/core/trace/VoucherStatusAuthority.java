package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which keys may publish a kind-30078 voucher-status event (cashu-ledger#17).
 *
 * <p>A status is accepted only when the event's own {@code pubkey} (never the self-asserted {@code
 * issuer_pubkey} tag) is either the key registered for the voucher's issuer in {@link
 * IssuerAttestationConfig}, or one of the gateway status signers the ledger is configured with.
 *
 * <p>An authority with no issuer keys and no gateway signers authorises nobody, so the watcher then
 * records nothing. That is deliberate: without a trust anchor there is nothing to check against.
 */
public final class VoucherStatusAuthority {

  private final IssuerAttestationConfig issuers;
  private final Set<String> gatewaySigners;

  public VoucherStatusAuthority(IssuerAttestationConfig issuers, Set<String> gatewaySigners) {
    this.issuers = issuers != null ? issuers : IssuerAttestationConfig.empty();
    this.gatewaySigners =
        gatewaySigners == null
            ? Set.of()
            : gatewaySigners.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
  }

  /** An authority that trusts nobody. */
  public static VoucherStatusAuthority none() {
    return new VoucherStatusAuthority(IssuerAttestationConfig.empty(), Set.of());
  }

  /** Whether any trust anchor is configured. */
  public boolean isEmpty() {
    return issuers.isEmpty() && gatewaySigners.isEmpty();
  }

  /** Whether {@code pubkey} is a configured gateway status signer. */
  public boolean isGatewaySigner(String pubkey) {
    return pubkey != null && gatewaySigners.contains(pubkey.toLowerCase(Locale.ROOT));
  }

  /**
   * Whether {@code signerPubkey} may publish status for a voucher of {@code issuerId}: the issuer's
   * registered key, or a gateway signer.
   */
  public boolean isAuthorised(String issuerId, String signerPubkey) {
    if (signerPubkey == null || signerPubkey.isBlank()) {
      return false;
    }
    return isGatewaySigner(signerPubkey) || issuers.isAuthorised(issuerId, signerPubkey);
  }
}
