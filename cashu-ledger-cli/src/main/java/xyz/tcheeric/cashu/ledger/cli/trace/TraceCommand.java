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
                ReplayCommand.class,
                ExportCommand.class
        })
public class TraceCommand implements Runnable {

    @CommandLine.Option(
            names = "--api",
            description = "Trace ledger API base URL",
            defaultValue = "http://localhost:8080/api/v1")
    private String apiBase;

    @CommandLine.Option(
            names = "--key",
            // A value passed on the command line is visible in `ps`, in the shell history file
            // and in any process listing the host ships to a log aggregator (audit M-27).
            // interactive=true makes picocli prompt on the terminal instead, and the environment
            // variable covers the scripted case; neither puts the key in the process table.
            interactive = true,
            arity = "0..1",
            defaultValue = "${env:CASHU_LEDGER_OPERATOR_KEY}",
            description = "Operator private key (hex) for NIP-98 authentication. Prompted for "
                    + "when omitted; may also be supplied via CASHU_LEDGER_OPERATOR_KEY. Avoid "
                    + "passing it inline: command-line arguments are world-readable in `ps`.")
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
