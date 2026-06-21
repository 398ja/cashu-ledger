package xyz.tcheeric.cashu.ledger.web.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import nostr.crypto.schnorr.Schnorr;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;

/**
 * Validates a NIP-98 HTTP-auth header ({@code Authorization: Nostr <base64 event>}).
 * Checks the kind-27235 event's kind, freshness, HTTP method, request URL, and
 * Schnorr signature, returning the authenticated pubkey. The event id is recomputed
 * with {@link CanonicalJson} (the same proven NIP-01 serialiser) so verification is
 * consistent with the rest of the system. Pure and unit-testable (no servlet types).
 */
public final class Nip98Validator {

    private static final int NIP98_KIND = 27235;
    private static final String SCHEME = "nostr ";
    private static final HexFormat HEX = HexFormat.of();

    private final ObjectMapper mapper = new ObjectMapper();
    private final long allowedSkewSeconds;
    private final LongSupplier nowMillis;

    public Nip98Validator(long allowedSkewSeconds, LongSupplier nowMillis) {
        this.allowedSkewSeconds = allowedSkewSeconds;
        this.nowMillis = nowMillis;
    }

    public static Nip98Validator withDefaults() {
        return new Nip98Validator(60, System::currentTimeMillis);
    }

    /**
     * Validates the header for a request to {@code requestUrl} via {@code httpMethod}.
     *
     * @return the authenticated lowercase pubkey hex
     * @throws Nip98Exception if the header is missing or any check fails
     */
    public String authenticate(String authorizationHeader, String httpMethod, String requestUrl) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            throw new Nip98Exception("missing or non-Nostr Authorization header");
        }
        String token = authorizationHeader.substring(SCHEME.length()).trim();
        JsonNode event = decode(token);

        requireField(event, "pubkey");
        requireField(event, "sig");
        requireField(event, "created_at");
        if (event.path("kind").asInt(-1) != NIP98_KIND) {
            throw new Nip98Exception("auth event kind must be " + NIP98_KIND);
        }

        long createdAt = event.get("created_at").asLong();
        long nowSeconds = nowMillis.getAsLong() / 1000L;
        if (Math.abs(nowSeconds - createdAt) > allowedSkewSeconds) {
            throw new Nip98Exception("auth event timestamp outside the allowed window");
        }

        List<List<String>> tags = parseTags(event.path("tags"));
        checkTag(tags, "method", httpMethod, true);
        checkUrl(tags, requestUrl);

        String pubkey = event.get("pubkey").asText().toLowerCase(Locale.ROOT);
        String content = event.path("content").asText("");
        String computedId = CanonicalJson.eventId(pubkey, createdAt, NIP98_KIND, tags, content);
        verifySignature(computedId, pubkey, event.get("sig").asText());
        return pubkey;
    }

    private JsonNode decode(String token) {
        try {
            byte[] decoded = decodeBase64(token);
            return mapper.readTree(decoded);
        } catch (Exception e) {
            throw new Nip98Exception("auth event is not valid base64 JSON");
        }
    }

    private static byte[] decodeBase64(String token) {
        try {
            return Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            return Base64.getDecoder().decode(token);
        }
    }

    private List<List<String>> parseTags(JsonNode tagsNode) {
        List<List<String>> tags = new ArrayList<>();
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                List<String> values = new ArrayList<>();
                for (JsonNode element : tag) {
                    values.add(element.asText());
                }
                tags.add(values);
            }
        }
        return tags;
    }

    private void checkTag(List<List<String>> tags, String name, String expected, boolean ignoreCase) {
        String value = firstTagValue(tags, name);
        if (value == null) {
            throw new Nip98Exception("auth event missing '" + name + "' tag");
        }
        boolean matches = ignoreCase ? value.equalsIgnoreCase(expected) : value.equals(expected);
        if (!matches) {
            throw new Nip98Exception("auth event '" + name + "' does not match the request");
        }
    }

    private void checkUrl(List<List<String>> tags, String requestUrl) {
        String u = firstTagValue(tags, "u");
        if (u == null) {
            throw new Nip98Exception("auth event missing 'u' tag");
        }
        // Compare path+query so a reverse proxy rewriting scheme/host does not break auth.
        if (!pathAndQuery(u).equals(pathAndQuery(requestUrl))) {
            throw new Nip98Exception("auth event 'u' does not match the request URL");
        }
    }

    private static String pathAndQuery(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static String firstTagValue(List<List<String>> tags, String name) {
        for (List<String> tag : tags) {
            if (tag.size() >= 2 && name.equals(tag.get(0))) {
                return tag.get(1);
            }
        }
        return null;
    }

    private void verifySignature(String eventId, String pubkey, String sigHex) {
        try {
            if (!Schnorr.verify(HEX.parseHex(eventId), HEX.parseHex(pubkey), HEX.parseHex(sigHex))) {
                throw new Nip98Exception("auth event signature verification failed");
            }
        } catch (Nip98Exception e) {
            throw e;
        } catch (Exception e) {
            throw new Nip98Exception("auth event signature could not be verified");
        }
    }

    private static void requireField(JsonNode event, String field) {
        if (event.get(field) == null || event.get(field).isNull()) {
            throw new Nip98Exception("auth event missing '" + field + "' field");
        }
    }
}
