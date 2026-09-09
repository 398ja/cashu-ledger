package xyz.tcheeric.cashu.ledger.cli.history;

import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.cli.CashuLedgerCommand;
import xyz.tcheeric.cashu.ledger.core.relay.NostrRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerServiceImpl;
import xyz.tcheeric.cashu.ledger.core.state.HistoryResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "history",
        description = "Display status change history for a voucher"
)
public class HistoryCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private CashuLedgerCommand parent;

    @CommandLine.Parameters(
            index = "0",
            description = "Voucher identifier (d-tag)"
    )
    private String voucherId;

    @CommandLine.Option(
            names = {"--since"},
            description = "Only include events after this ISO-8601 timestamp"
    )
    private String since;

    @CommandLine.Option(
            names = {"--until"},
            description = "Only include events before this ISO-8601 timestamp"
    )
    private String until;

    @CommandLine.Option(
            names = {"--limit"},
            description = "Maximum events to fetch",
            defaultValue = "50"
    )
    private int limit;

    @Override
    public Integer call() {
        try (VoucherLedgerService service = buildService()) {
            HistoryResult history = service.fetchHistory(
                    voucherId,
                    parseInstant(since),
                    parseInstant(until),
                    limit
            );
            HistoryFormatter formatter = new HistoryFormatter(parent.outputFormat());
            System.out.println(formatter.format(voucherId, history));
            return history.warnings().isEmpty() ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
        } catch (Exception e) {
            System.err.println("History failed: " + e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    private VoucherLedgerService buildService() {
        int timeout = parent.timeoutSeconds();
        return new VoucherLedgerServiceImpl(
                new NostrRelayConnectionManager(),
                parent.relayUrls(),
                Duration.ofSeconds(timeout),
                Duration.ofSeconds(timeout),
                null,                          // default cache TTL
                parent.issuerAttestation()     // --issuer-key; empty means nothing is trusted
        );
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}
