package xyz.tcheeric.cashu.ledger.web.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A proof in an API response. Secret-bearing fields ({@code secret}, {@code c},
 * {@code witness}) are present only when the caller's access level permits and the
 * stored event carried them; otherwise they are omitted (design §5.5 / §7.3).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProofView(
        long amount,
        String keysetId,
        String y,
        String secret,
        String c,
        String witness
) {
}
