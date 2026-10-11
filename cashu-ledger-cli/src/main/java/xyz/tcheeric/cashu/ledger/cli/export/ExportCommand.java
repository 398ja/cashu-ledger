package xyz.tcheeric.cashu.ledger.cli.export;

import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.cli.CashuLedgerCommand;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;
import xyz.tcheeric.cashu.ledger.core.relay.NostrRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerServiceImpl;
import xyz.tcheeric.cashu.ledger.core.state.StatusChange;
import xyz.tcheeric.cashu.ledger.core.state.VoucherSearchCriteria;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "export",
        description = "Export voucher data (single, tree, or history) as JSON/CSV"
)
public class ExportCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private CashuLedgerCommand parent;

    @CommandLine.Parameters(
            index = "0",
            description = "Voucher identifier (d-tag)"
    )
    private String voucherId;

    @CommandLine.Option(
            names = {"--format"},
            description = "Output format: json, csv",
            defaultValue = "json"
    )
    private String format;

    @CommandLine.Option(
            names = {"--tree"},
            description = "Include full voucher tree"
    )
    private boolean includeTree;

    @CommandLine.Option(
            names = {"--history"},
            description = "Include status history"
    )
    private boolean includeHistory;

    @CommandLine.Option(
            names = {"--since"},
            description = "History lower bound (ISO-8601)"
    )
    private String since;

    @CommandLine.Option(
            names = {"--until"},
            description = "History upper bound (ISO-8601)"
    )
    private String until;

    @Override
    public Integer call() {
        try (VoucherLedgerService service = buildService()) {
            Optional<VoucherNode> node = service.fetchVoucher(voucherId);
            if (node.isEmpty()) {
                System.err.println("Voucher not found: " + voucherId);
                return CommandLine.ExitCode.SOFTWARE;
            }

            VoucherTree tree = null;
            if (includeTree) {
                tree = service.buildTree(voucherId, 10, xyz.tcheeric.cashu.ledger.core.model.TraversalDirection.BOTH)
                        .orElse(null);
            }

            List<StatusChange> history = List.of();
            List<String> historyWarnings = List.of();
            if (includeHistory) {
                var historyResult = service.fetchHistory(voucherId, parseInstant(since), parseInstant(until), 200);
                history = historyResult.events();
                historyWarnings = historyResult.warnings();
            }

            ExportFormatter formatter = new ExportFormatter(format);
            System.out.println(formatter.format(node.get(), tree, history, historyWarnings));
            return historyWarnings.isEmpty() ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
        } catch (Exception e) {
            System.err.println("Export failed: " + e.getMessage());
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
