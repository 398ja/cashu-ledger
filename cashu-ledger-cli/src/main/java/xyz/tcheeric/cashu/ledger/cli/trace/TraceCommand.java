package xyz.tcheeric.cashu.ledger.cli.trace;

import java.util.Optional;
import picocli.CommandLine;

/**
 * Parent command for the transaction-traceability queries. Holds the shared connection
 * options (API base URL, the operator key used for NIP-98 authentication, and the output
 * format) and exposes a configured {@link TraceApiClient} to its subcommands.
 */
@CommandLine.Command(
        name = "trace",
        description = "Query the transaction-traceability ledger",
        mixinStandardHelpOptions = true,
        subcommands = {
                TraceProofCommand.class,
                TraceVoucherCommand.class,
                TraceIssuerCommand.class,
                ReplayCommand.class
        })
public class TraceCommand implements Runnable {

    @CommandLine.Option(
            names = "--api",
            description = "Trace ledger API base URL",
            defaultValue = "http://localhost:8080/api/v1")
    private String apiBase;

    @CommandLine.Option(
            names = "--key",
            description = "Operator private key (hex) for NIP-98 authentication")
    private String operatorKey;

    @CommandLine.Option(
            names = {"-o", "--output"},
            description = "Output format: text, json, tree",
            defaultValue = "text")
    private String output;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public TraceApiClient client() {
        Optional<String> key = Optional.ofNullable(operatorKey)
                .map(String::trim).filter(k -> !k.isEmpty());
        return new TraceApiClient(apiBase, key);
    }

    public String output() {
        return output;
    }
}
