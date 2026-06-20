package xyz.tcheeric.cashu.ledger.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * Lists the transaction events bound to a voucher (design §5.5 / FR-024), optionally filtered
 * by activity (active / terminal / all).
 */
@CommandLine.Command(name = "voucher", description = "List the trace events bound to a voucher")
public class TraceVoucherCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private TraceCommand parent;

    @CommandLine.Parameters(index = "0", description = "Voucher id")
    private String voucherId;

    @CommandLine.Option(names = "--activity", description = "Activity filter: active, terminal, all",
            defaultValue = "all")
    private String activity;

    @Override
    public Integer call() {
        String path = "/trace/vouchers/" + enc(voucherId) + "/events?activity=" + enc(activity);
        try {
            JsonNode page = parent.client().get(path);
            if (page == null) {
                System.err.println("No events for voucher " + voucherId);
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
