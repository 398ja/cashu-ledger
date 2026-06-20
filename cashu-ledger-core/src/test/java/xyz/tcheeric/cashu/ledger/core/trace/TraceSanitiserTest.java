package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TraceSanitiser}: re-keying secret fields under an ephemeral key,
 * stripping the bundleToken, tagging the export, and refusing the production key.
 */
class TraceSanitiserTest {

    private static final byte[] KEY = "ephemeralephemeralephemeral12345".getBytes(StandardCharsets.UTF_8);
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String EVENT = """
        {"eventId":"e1","kind":"swap","bundleToken":"cashuAbc",
         "inputs":[{"amount":64,"keysetId":"00ad12ef","y":"02aa","secret":"RAWSECRET","c":"RAWC"}],
         "lightning":{"quoteId":"q1","bolt11":"lnbc1RAW"}}
        """;

    /** Secrets are replaced with HMACs (not the raw values), the token is removed, tag added. */
    @Test
    void shouldReKeySecretsStripBundleAndTag() throws Exception {
        // Given: an event view carrying raw secrets and a bundle token
        JsonNode event = mapper.readTree(EVENT);

        // When: sanitising
        JsonNode out = new TraceSanitiser(KEY).sanitise(event);

        // Then: raw secrets are gone, replaced by HMAC hex; token stripped; sanitised flag set
        String secret = out.path("inputs").get(0).path("secret").asText();
        assertThat(secret).isNotEqualTo("RAWSECRET").hasSize(64);
        assertThat(out.path("inputs").get(0).path("c").asText()).isNotEqualTo("RAWC");
        assertThat(out.path("lightning").path("bolt11").asText()).isNotEqualTo("lnbc1RAW");
        assertThat(out.has("bundleToken")).isFalse();
        assertThat(out.path("sanitised").asBoolean()).isTrue();
    }

    /** Sanitising with the same ephemeral key is deterministic. */
    @Test
    void shouldBeDeterministicForSameKey() throws Exception {
        JsonNode event = mapper.readTree(EVENT);
        String first = new TraceSanitiser(KEY).sanitise(event).path("inputs").get(0).path("secret").asText();
        String second = new TraceSanitiser(KEY).sanitise(event).path("inputs").get(0).path("secret").asText();
        assertThat(first).isEqualTo(second);
    }

    /** Sanitising refuses to run with the production redaction key. */
    @Test
    void shouldRefuseProductionKey() {
        // Given: an ephemeral key that (here) equals the production key
        TraceSanitiser sanitiser = new TraceSanitiser(KEY);

        // When/Then: the production-key guard refuses
        assertThatThrownBy(() -> sanitiser.assertNotProductionKey(KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("production redaction key");
    }
}
