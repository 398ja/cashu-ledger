package xyz.tcheeric.cashu.ledger.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.core.trace.TraceSanitiser;

/**
 * Exports trace events as newline-delimited JSON. With {@code --sanitise} each event is
 * re-keyed under a fresh ephemeral HMAC key (never the production redaction key), its
 * bundleToken is stripped, and it is tagged {@code sanitised:true} — safe to share off-system
 * (design §7.2). Events are fetched from the read API, so they are already shaped to the
 * caller's access level before sanitisation.
 */
@CommandLine.Command(name = "export", description = "Export trace events, optionally sanitised for sharing")
public class ExportCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private TraceCommand parent;

    @CommandLine.Option(names = "--mint", description = "Restrict to a mint URL")
    private String mintUrl;

    @CommandLine.Option(names = "--limit", description = "Maximum events to export", defaultValue = "100")
    private int limit;

    @CommandLine.Option(names = "--sanitise", description = "Re-key secrets with an ephemeral key for sharing")
    private boolean sanitise;

    @Override
    public Integer call() {
        StringBuilder path = new StringBuilder("/trace/events?limit=").append(limit);
        if (mintUrl != null && !mintUrl.isBlank()) {
            path.append("&mintUrl=").append(URLEncoder.encode(mintUrl, StandardCharsets.UTF_8));
        }
        try {
            JsonNode page = parent.client().get(path.toString());
            if (page == null || !page.path("events").isArray()) {
                System.err.println("No events to export.");
                return CommandLine.ExitCode.OK;
            }
            TraceSanitiser sanitiser = sanitise ? TraceSanitiser.withEphemeralKey() : null;
            int exported = 0;
            for (JsonNode event : page.path("events")) {
                JsonNode out = sanitiser != null ? sanitiser.sanitise(event) : event;
                System.out.println(out.toString());
                exported++;
            }
            System.err.println("export_complete events=" + exported + " sanitised=" + sanitise);
            return CommandLine.ExitCode.OK;
        } catch (TraceApiException e) {
            System.err.println(e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }
}
