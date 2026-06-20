package xyz.tcheeric.cashu.ledger.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * Lists the transaction events attributed to an issuer (design §5.5 / FR-024). By default the
 * id is matched against {@code issuer_id}; {@code --by-pubkey} matches {@code issuer_pubkey}.
 * Results may be narrowed by activity.
 */
@CommandLine.Command(name = "issuer", description = "List the trace events attributed to an issuer")
public class TraceIssuerCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private TraceCommand parent;

    @CommandLine.Parameters(index = "0", description = "Issuer id (or pubkey with --by-pubkey)")
    private String issuerId;

    @CommandLine.Option(names = "--by-pubkey", description = "Match the issuer pubkey instead of the id")
    private boolean byPubkey;

    @CommandLine.Option(names = "--activity", description = "Activity filter: active, terminal, all",
            defaultValue = "all")
    private String activity;

    @Override
    public Integer call() {
        String path = "/trace/issuers/" + enc(issuerId) + "/events"
                + "?byPubkey=" + byPubkey + "&activity=" + enc(activity);
        try {
            JsonNode page = parent.client().get(path);
            if (page == null) {
                System.err.println("No events for issuer " + issuerId);
                return CommandLine.ExitCode.SOFTWARE;
            }
            System.out.println(TraceEventListFormatter.format(page, parent.output()));
            return CommandLine.ExitCode.OK;
        } catch (TraceApiException e) {
            System.err.println(e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
