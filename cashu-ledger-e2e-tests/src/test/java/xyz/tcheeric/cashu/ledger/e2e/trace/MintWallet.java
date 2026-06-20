package xyz.tcheeric.cashu.ledger.e2e.trace;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.entities.rest.GetKeySetsResponse;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteBolt11Request;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.wallet.client.impl.RequestActiveKeysets;
import xyz.tcheeric.cashu.wallet.client.impl.RequestKeySetPublicKey;
import xyz.tcheeric.cashu.wallet.client.impl.RequestMintQuote;
import xyz.tcheeric.cashu.wallet.client.impl.RequestMintToken;
import xyz.tcheeric.cashu.wallet.client.impl.RequestSwapToken;
import xyz.tcheeric.cashu.wallet.proto.nut.NUT04;

/**
 * A minimal real Cashu wallet over the wallet-client request classes and the
 * {@code cashu-lib-crypto} BDHKE primitives, used only by {@link TraceCaptureE2ETest}.
 *
 * <p>It performs genuine {@code mint} and {@code swap} round-trips against a live mint:
 * blinding each output as {@code B_ = hash_to_curve(secret) + r·G}, sending the blinded
 * messages, and unblinding the mint's real blind signatures into spendable proofs via
 * {@link NUT04#unblindingSignature}. The minted proofs are then spent as swap inputs,
 * which only succeeds if the end-to-end BDHKE is correct — making the produced proofs
 * authentic mint output rather than fixtures.</p>
 */
final class MintWallet {

    private static final ECNamedCurveParameterSpec SECP256K1 =
            ECNamedCurveTable.getParameterSpec("secp256k1");
    private static final ECPoint G = SECP256K1.getG();
    private static final BigInteger N = SECP256K1.getN();
    private static final String UNIT = "sat";

    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();
    private final org.springframework.web.client.RestTemplate restTemplate =
            new org.springframework.web.client.RestTemplate();

    /** @param baseUrl mint REST base including the version segment, e.g. {@code http://host:32768/v1} */
    MintWallet(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** A blinded output together with the client-side secret and blinding factor needed to unblind. */
    private record BlindedOutput(BlindedMessage message, RandomStringSecret secret, BigInteger r) {
    }

    /** The active {@code sat} keyset: its id plus the per-amount public keys. */
    private record SatKeyset(KeysetId id, Keys keys) {
    }

    /** A completed mint: the spendable proofs together with the bolt11 quote that funded them. */
    record Minted(List<Proof<RandomStringSecret>> proofs, String quoteId, int amount) {
    }

    /**
     * Mints {@code amount} sats into real proofs: requests a bolt11 quote, waits for the
     * mock Lightning backend to settle it, then blinds, mints, and unblinds the outputs.
     */
    Minted mint(int amount) throws Exception {
        SatKeyset keyset = fetchSatKeyset();
        PostMintQuoteResponse quote = new RequestMintQuote(
                baseUrl, PaymentMethod.BOLT11, new PostMintQuoteBolt11Request(amount)).execute();
        awaitPaid(quote.getQuoteId());

        List<BlindedOutput> outputs = blindOutputs(amount, keyset);
        PostMintRequest<RandomStringSecret> request = new PostMintRequest<>();
        request.setQuoteId(quote.getQuoteId());
        request.setBlindedMessages(messages(outputs));
        request.setSecrets(secrets(outputs));
        request.setBlindingFactors(blindingFactors(outputs));

        PostMintResponse response =
                new RequestMintToken<>(baseUrl, PaymentMethod.BOLT11, request).execute();
        List<Proof<RandomStringSecret>> proofs =
                unblind(response.getBlindSignatures(), outputs, keyset.keys());
        return new Minted(proofs, quote.getQuoteId(), amount);
    }

    /**
     * Swaps {@code inputs} for a fresh set of proofs of the same total amount, proving the
     * minted proofs are spendable. Inputs and outputs balance with a zero fee.
     */
    List<Proof<RandomStringSecret>> swap(List<Proof<RandomStringSecret>> inputs) throws Exception {
        SatKeyset keyset = fetchSatKeyset();
        int total = inputs.stream().mapToInt(Proof::getAmount).sum();
        List<BlindedOutput> outputs = blindOutputs(total, keyset);

        PostSwapResponse response = new RequestSwapToken<>(
                baseUrl, new PostSwapRequest<>(inputs, messages(outputs))).execute();
        return unblind(response.getBlindSignatures(), outputs, keyset.keys());
    }

    private SatKeyset fetchSatKeyset() {
        String activeSatKeysetId = new RequestActiveKeysets(baseUrl).execute()
                .getActiveKeySets().stream()
                .filter(keySet -> keySet.isActive() && UNIT.equals(keySet.getUnit()))
                .map(ActiveKeySet::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("mint exposes no active sat keyset"));

        GetKeySetsResponse response =
                new RequestKeySetPublicKey(baseUrl, activeSatKeysetId).execute();
        KeySet satKeyset = response.getKeySets().stream()
                .filter(keySet -> activeSatKeysetId.equals(keySet.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "mint returned no keys for keyset " + activeSatKeysetId));
        return new SatKeyset(KeysetId.fromString(satKeyset.getId()), satKeyset.getKeys());
    }

    private void awaitPaid(String quoteId) throws Exception {
        // NUT-04 quote-state path is /v1/mint/quote/bolt11/{quoteId}; queried directly because
        // this wallet-client build orders the path segments the other way round.
        String url = baseUrl + "/mint/quote/bolt11/" + quoteId;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (isPaid(url)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("mint quote " + quoteId + " was not settled within 30s");
    }

    /** Returns whether the quote is settled; an unpaid quote answers HTTP 402, which is expected. */
    private boolean isPaid(String url) {
        try {
            PostMintQuoteResponse state = restTemplate.getForObject(url, PostMintQuoteResponse.class);
            return state != null && state.isPaid();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 402) {
                return false;
            }
            throw e;
        }
    }

    private List<BlindedOutput> blindOutputs(int amount, SatKeyset keyset) {
        List<BlindedOutput> outputs = new ArrayList<>();
        for (int denomination : decompose(amount)) {
            RandomStringSecret secret = RandomStringSecret.create();
            BigInteger r = randomScalar();
            ECPoint y = BDHKEUtils.hashToCurve(secret.getBytes());
            ECPoint blindedPoint = y.add(G.multiply(r)).normalize();
            BlindedMessage message = new BlindedMessage(
                    denomination, keyset.id(), PublicKey.fromPoint(blindedPoint), null);
            outputs.add(new BlindedOutput(message, secret, r));
        }
        return outputs;
    }

    private List<Proof<RandomStringSecret>> unblind(List<BlindSignature> signatures,
                                                    List<BlindedOutput> outputs, Keys keys) {
        List<Proof<RandomStringSecret>> proofs = new ArrayList<>();
        for (int i = 0; i < signatures.size(); i++) {
            BlindedOutput output = outputs.get(i);
            PublicKey mintKey = keys.get(output.message().getAmount());
            proofs.add(NUT04.unblindingSignature(
                    signatures.get(i), output.r(), mintKey, output.secret()));
        }
        return proofs;
    }

    private BigInteger randomScalar() {
        BigInteger r;
        do {
            r = new BigInteger(N.bitLength(), random).mod(N);
        } while (r.signum() == 0);
        return r;
    }

    /** Decomposes an amount into the keyset's power-of-two denominations, largest first. */
    private static List<Integer> decompose(int amount) {
        List<Integer> denominations = new ArrayList<>();
        for (int bit = Integer.highestOneBit(amount); bit > 0; bit >>= 1) {
            if ((amount & bit) != 0) {
                denominations.add(bit);
            }
        }
        return denominations;
    }

    private static List<BlindedMessage> messages(List<BlindedOutput> outputs) {
        return outputs.stream().map(BlindedOutput::message).toList();
    }

    private static List<RandomStringSecret> secrets(List<BlindedOutput> outputs) {
        return outputs.stream().map(BlindedOutput::secret).toList();
    }

    private static List<byte[]> blindingFactors(List<BlindedOutput> outputs) {
        return outputs.stream().map(output -> output.r().toByteArray()).toList();
    }
}
