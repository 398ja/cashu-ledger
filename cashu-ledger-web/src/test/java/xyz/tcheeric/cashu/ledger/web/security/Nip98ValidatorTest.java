package xyz.tcheeric.cashu.ledger.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;

/**
 * Unit tests for {@link Nip98Validator}: a correctly signed NIP-98 header
 * authenticates, while tampering, wrong method/URL, staleness, and a missing
 * header are all rejected.
 */
class Nip98ValidatorTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String PRIV =
            "0000000000000000000000000000000000000000000000000000000000000031";
    private static final String PUB = derivePub();
    private static final long NOW_SEC = 1_700_000_000L;
    private static final String URL = "http://localhost:6060/api/v1/trace/events?mintUrl=x";

    private final Nip98Validator validator =
            new Nip98Validator(60, () -> NOW_SEC * 1000);

    private static String derivePub() {
        try {
            return HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(PRIV)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String header(String method, String url, long createdAt) throws Exception {
        ObjectMapper om = new ObjectMapper();
        List<List<String>> tags = List.of(List.of("u", url), List.of("method", method));
        String content = "";
        String id = CanonicalJson.eventId(PUB, createdAt, 27235, tags, content);
        byte[] sig = Schnorr.sign(HEX.parseHex(id), HEX.parseHex(PRIV), new byte[32]);
        ObjectNode ev = om.createObjectNode();
        ev.put("id", id);
        ev.put("pubkey", PUB);
        ev.put("created_at", createdAt);
        ev.put("kind", 27235);
        ArrayNode tagsNode = ev.putArray("tags");
        for (List<String> tag : tags) {
            ArrayNode t = tagsNode.addArray();
            tag.forEach(t::add);
        }
        ev.put("content", content);
        ev.put("sig", HEX.formatHex(sig));
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(om.writeValueAsString(ev).getBytes(StandardCharsets.UTF_8));
        return "Nostr " + token;
    }

    /** Tests that a correctly signed header authenticates and returns the pubkey. */
    @Test
    void shouldAuthenticateValidHeader() throws Exception {
        // Act
        String pubkey = validator.authenticate(header("GET", URL, NOW_SEC), "GET", URL);

        // Then
        assertThat(pubkey).isEqualTo(PUB);
    }

    /** Tests that a missing or non-Nostr header is rejected. */
    @Test
    void shouldRejectMissingHeader() {
        // Act / Then
        assertThatThrownBy(() -> validator.authenticate(null, "GET", URL))
                .isInstanceOf(Nip98Exception.class);
        assertThatThrownBy(() -> validator.authenticate("Bearer xyz", "GET", URL))
                .isInstanceOf(Nip98Exception.class);
    }

    /** Tests that a tampered signature fails verification. */
    @Test
    void shouldRejectTamperedSignature() throws Exception {
        // Arrange: replace the signature with an all-zero (invalid) signature
        String good = header("GET", URL, NOW_SEC);
        String json = new String(Base64.getUrlDecoder().decode(good.substring(6)), StandardCharsets.UTF_8);
        String tampered = json.replaceAll("\"sig\":\"[0-9a-f]+\"", "\"sig\":\"" + "0".repeat(128) + "\"");
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tampered.getBytes(StandardCharsets.UTF_8));

        // Act / Then
        assertThatThrownBy(() -> validator.authenticate("Nostr " + token, "GET", URL))
                .isInstanceOf(Nip98Exception.class);
    }

    /** Tests that a method mismatch is rejected. */
    @Test
    void shouldRejectMethodMismatch() throws Exception {
        // Arrange: event signed for GET, request is POST
        String headerForGet = header("GET", URL, NOW_SEC);

        // Act / Then
        assertThatThrownBy(() -> validator.authenticate(headerForGet, "POST", URL))
                .isInstanceOf(Nip98Exception.class)
                .hasMessageContaining("method");
    }

    /** Tests that a URL mismatch is rejected. */
    @Test
    void shouldRejectUrlMismatch() throws Exception {
        // Arrange
        String headerForUrl = header("GET", URL, NOW_SEC);

        // Act / Then
        assertThatThrownBy(() -> validator.authenticate(headerForUrl, "GET",
                "http://localhost:6060/api/v1/trace/events?mintUrl=OTHER"))
                .isInstanceOf(Nip98Exception.class)
                .hasMessageContaining("URL");
    }

    /** Tests that a stale auth-event timestamp is rejected. */
    @Test
    void shouldRejectStaleTimestamp() throws Exception {
        // Arrange: created 1 hour before "now"
        String stale = header("GET", URL, NOW_SEC - 3600);

        // Act / Then
        assertThatThrownBy(() -> validator.authenticate(stale, "GET", URL))
                .isInstanceOf(Nip98Exception.class)
                .hasMessageContaining("window");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("an auth event cannot be presented twice")
    void authEventIsSingleUse() throws Exception {
        // The signature proves who authored the event; it says nothing about how many times it
        // has been presented. Anyone who observed one Authorization header could resend it until
        // the skew window closed (audit M-25).
        String token = header("GET", URL, NOW_SEC);

        assertThat(validator.authenticate(token, "GET", URL)).isEqualTo(PUB);

        assertThatThrownBy(() -> validator.authenticate(token, "GET", URL))
            .as("the same event replayed inside the window must be refused")
            .hasMessageContaining("already been used");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a distinct auth event still works after one is used")
    void distinctEventsStillWork() throws Exception {
        validator.authenticate(header("GET", URL, NOW_SEC), "GET", URL);

        // A different created_at yields a different event id, which is the ordinary case: each
        // request signs its own event.
        assertThat(validator.authenticate(header("GET", URL, NOW_SEC - 1), "GET", URL))
            .isEqualTo(PUB);
    }

}
