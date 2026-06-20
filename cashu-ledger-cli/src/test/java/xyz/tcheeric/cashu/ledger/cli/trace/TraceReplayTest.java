package xyz.tcheeric.cashu.ledger.cli.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.publisher.SignedTraceEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceEventSigner;

/**
 * Unit tests for {@link TraceReplay}: a replayed operation re-signs to a deterministic event
 * id (the basis for idempotent backfill, FR-19b), and reordering the same proofs does not
 * change that id.
 */
class TraceReplayTest {

    private static final String PRIV =
            "0000000000000000000000000000000000000000000000000000000000000007";
    private final ObjectMapper mapper = new ObjectMapper();
    private final TraceReplay replay = new TraceReplay(new TraceEventSigner(PRIV));

    private static final String OP = """
        {"mintUrl":"https://mint.imani.casa","op":"swap","unit":"sat",
         "transitionAt":"2026-01-01T00:00:00Z","producerPubkey":"%s",
         "inputs":[{"amount":64,"keysetId":"00ad12ef","y":"02aa","secret":"s1","c":"c1"}],
         "outputs":[{"amount":32,"keysetId":"00ad12ef","y":"02bb"},
                    {"amount":32,"keysetId":"00ad12ef","y":"02cc"}]}
        """;

    /** Replaying the same operation twice yields the same signed event id. */
    @Test
    void shouldProduceDeterministicEventIdAcrossReplays() throws Exception {
        // Given: a producer pubkey from the signing key
        String pub = new TraceEventSigner(PRIV).publicKeyHex();
        JsonNode op = mapper.readTree(String.format(OP, pub));

        // When: signing the same operation twice
        SignedTraceEvent first = replay.toSignedEvent(op);
        SignedTraceEvent second = replay.toSignedEvent(op);

        // Then: identical event ids (idempotent backfill)
        assertThat(first.eventId()).isEqualTo(second.eventId());
        assertThat(first.eventId()).isNotBlank();
    }

    /** The deterministic backfill operation id is independent of proof order. */
    @Test
    void shouldDeriveOrderIndependentOperationId() throws Exception {
        String pub = new TraceEventSigner(PRIV).publicKeyHex();
        JsonNode ordered = mapper.readTree(String.format(OP, pub));
        JsonNode reordered = mapper.readTree(String.format("""
            {"mintUrl":"https://mint.imani.casa","op":"swap","unit":"sat",
             "transitionAt":"2026-01-01T00:00:00Z","producerPubkey":"%s",
             "inputs":[{"amount":64,"keysetId":"00ad12ef","y":"02aa","secret":"s1","c":"c1"}],
             "outputs":[{"amount":32,"keysetId":"00ad12ef","y":"02cc"},
                        {"amount":32,"keysetId":"00ad12ef","y":"02bb"}]}
            """, pub));

        // When: signing both orderings (operation_id is carried in the "d" tag)
        String orderedOpId = dTag(replay.toSignedEvent(ordered).eventJson());
        String reorderedOpId = dTag(replay.toSignedEvent(reordered).eventJson());

        // Then: the backfill operation id is order-independent (sorted Ys)
        assertThat(orderedOpId).isEqualTo(reorderedOpId);
        assertThat(orderedOpId).isNotBlank();
    }

    private String dTag(String eventJson) throws Exception {
        for (JsonNode tag : mapper.readTree(eventJson).path("tags")) {
            if (tag.isArray() && tag.size() >= 2 && "d".equals(tag.get(0).asText())) {
                return tag.get(1).asText();
            }
        }
        throw new AssertionError("no d (operation_id) tag in signed event");
    }
}
