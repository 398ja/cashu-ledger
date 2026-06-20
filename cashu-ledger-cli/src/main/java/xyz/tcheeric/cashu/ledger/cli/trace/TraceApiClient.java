package xyz.tcheeric.cashu.ledger.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import nostr.crypto.schnorr.Schnorr;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;

/**
 * Minimal HTTP client for the trace read API. Sends GET requests against a configured base
 * URL ({@code .../api/v1}) and, when a signing key is supplied, attaches a NIP-98 (kind
 * 27235) Authorization header so the operator-gated endpoints accept the call. Returns the
 * parsed JSON body; only proof {@code Y} values — never raw secrets — ever leave the client.
 */
public final class TraceApiClient {

    private static final HexFormat HEX = HexFormat.of();
    private static final int NIP98_KIND = 27235;

    private final String baseUrl;
    private final Optional<String> privateKeyHex;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public TraceApiClient(String baseUrl, Optional<String> privateKeyHex) {
        this.baseUrl = baseUrl.replaceFirst("/$", "");
        this.privateKeyHex = privateKeyHex;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Issues a GET against {@code path} (e.g. {@code /trace/proofs/02ab}) and parses the body. */
    public JsonNode get(String path) {
        String url = baseUrl + path;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).GET();
            authHeader("GET", url).ifPresent(header -> request.header("Authorization", header));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return handle(url, response);
        } catch (IOException e) {
            throw new TraceApiException("Failed to reach trace API at " + url
                    + ". Check the --api base URL and that the ledger is running.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TraceApiException("Interrupted while calling " + url, e);
        }
    }

    private JsonNode handle(String url, HttpResponse<String> response) throws IOException {
        int status = response.statusCode();
        if (status == 401) {
            throw new TraceApiException("Unauthorised (401) for " + url
                    + ". Supply an operator key with --key to authenticate.");
        }
        if (status == 409) {
            throw new TraceApiException("Ambiguous proof (409) for " + url
                    + ". Supply --mint and --keyset to disambiguate.");
        }
        if (status == 404) {
            return null;
        }
        if (status >= 400) {
            throw new TraceApiException("Trace API returned " + status + " for " + url
                    + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    private Optional<String> authHeader(String method, String url) {
        if (privateKeyHex.isEmpty()) {
            return Optional.empty();
        }
        try {
            byte[] priv = HEX.parseHex(privateKeyHex.get().trim());
            String pub = HEX.formatHex(Schnorr.genPubKey(priv));
            long createdAt = Instant.now().getEpochSecond();
            List<List<String>> tags = List.of(List.of("u", url), List.of("method", method));
            String id = CanonicalJson.eventId(pub, createdAt, NIP98_KIND, tags, "");
            byte[] sig = Schnorr.sign(HEX.parseHex(id), priv, new byte[32]);

            ObjectNode event = mapper.createObjectNode();
            event.put("id", id);
            event.put("pubkey", pub);
            event.put("created_at", createdAt);
            event.put("kind", NIP98_KIND);
            ArrayNode tagsNode = event.putArray("tags");
            for (List<String> tag : tags) {
                ArrayNode t = tagsNode.addArray();
                tag.forEach(t::add);
            }
            event.put("content", "");
            event.put("sig", HEX.formatHex(sig));
            String token = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
            return Optional.of("Nostr " + token);
        } catch (Exception e) {
            throw new TraceApiException("Failed to build NIP-98 authentication header: "
                    + e.getMessage(), e);
        }
    }
}
