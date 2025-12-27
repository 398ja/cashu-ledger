package xyz.tcheeric.cashu.ledger.cli;

import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.cli.inspect.InspectCommand;
import xyz.tcheeric.cashu.ledger.cli.tree.TreeCommand;
import xyz.tcheeric.cashu.ledger.cli.search.SearchCommand;
import xyz.tcheeric.cashu.ledger.cli.history.HistoryCommand;
import xyz.tcheeric.cashu.ledger.cli.export.ExportCommand;
import xyz.tcheeric.cashu.ledger.cli.unclaimed.UnclaimedCommand;
import xyz.tcheeric.cashu.ledger.cli.verify.VerifyCommand;
import xyz.tcheeric.cashu.ledger.cli.diff.DiffCommand;
import xyz.tcheeric.cashu.ledger.cli.watch.WatchCommand;

import java.util.ArrayList;
import java.util.List;

@CommandLine.Command(
        name = "cashu-ledger",
        description = "Inspect and analyze vouchers stored on Nostr relays",
        mixinStandardHelpOptions = true,
        subcommands = {
                InspectCommand.class,
                TreeCommand.class,
                SearchCommand.class,
                HistoryCommand.class,
                ExportCommand.class,
                UnclaimedCommand.class,
                VerifyCommand.class,
                DiffCommand.class,
                WatchCommand.class
        }
)
public class CashuLedgerCommand implements Runnable {

    @CommandLine.Option(
            names = {"-r", "--relay"},
            description = "Nostr relay URL (repeatable)",
            split = ","
    )
    private List<String> relayUrls = new ArrayList<>();

    @CommandLine.Option(
            names = {"-t", "--timeout"},
            description = "Query timeout in seconds",
            defaultValue = "30"
    )
    private int timeoutSeconds;

    @CommandLine.Option(
            names = {"-o", "--output"},
            description = "Output format: text, json, tree",
            defaultValue = "text"
    )
    private String outputFormat;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public List<String> relayUrls() {
        if (relayUrls == null || relayUrls.isEmpty()) {
            return List.of("wss://relay.imani.casa");
        }
        return relayUrls;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public String outputFormat() {
        return outputFormat;
    }
}
