package xyz.tcheeric.cashu.ledger.cli.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AsciiDagRenderer}: rendering a walk response as an ASCII tree,
 * labelling edges, marking revisited nodes, and noting truncation.
 */
class AsciiDagRendererTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AsciiDagRenderer renderer = new AsciiDagRenderer();

    /** A linear chain renders root-first with the child indented under its edge label. */
    @Test
    void shouldRenderLinearChainAsTree() throws Exception {
        // Given: mint --spend 8--> swap
        JsonNode walk = mapper.readTree("""
            {"nodes":[{"eventId":"mint-a","kind":"mint"},
                      {"eventId":"swap-b","kind":"swap"}],
             "edges":[{"fromEventId":"mint-a","toEventId":"swap-b",
                       "role":"spend","amount":8}],
             "truncated":false}
            """);

        // When: rendering
        String tree = renderer.render(walk);

        // Then: the mint is the root and the swap descends under a spend edge label
        assertThat(tree).contains("mint mint-a");
        assertThat(tree).contains("[spend 8] swap swap-b");
    }

    /** A truncated walk appends a truncation notice. */
    @Test
    void shouldNoteTruncation() throws Exception {
        // Given: a single-node truncated walk
        JsonNode walk = mapper.readTree("""
            {"nodes":[{"eventId":"e-1","kind":"mint"}],"edges":[],"truncated":true}
            """);

        // When: rendering
        String tree = renderer.render(walk);

        // Then: the truncation hint is present
        assertThat(tree).contains("truncated");
    }

    /** A shared descendant is printed once and marked (seen) on revisit. */
    @Test
    void shouldMarkRevisitedNodesAsSeen() throws Exception {
        // Given: two parents pointing at the same child
        JsonNode walk = mapper.readTree("""
            {"nodes":[{"eventId":"a","kind":"mint"},{"eventId":"b","kind":"mint"},
                      {"eventId":"c","kind":"swap"}],
             "edges":[{"fromEventId":"a","toEventId":"c","role":"spend"},
                      {"fromEventId":"b","toEventId":"c","role":"spend"}],
             "truncated":false}
            """);

        // When: rendering
        String tree = renderer.render(walk);

        // Then: the shared child is annotated (seen) on its second appearance
        assertThat(tree).contains("(seen)");
    }
}
