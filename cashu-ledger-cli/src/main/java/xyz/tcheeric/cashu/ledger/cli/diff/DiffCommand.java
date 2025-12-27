package xyz.tcheeric.cashu.ledger.cli.diff;

import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.cli.CashuLedgerCommand;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.relay.NostrRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerServiceImpl;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "diff",
        description = "Compare two vouchers"
)
public class DiffCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private CashuLedgerCommand parent;

    @CommandLine.Parameters(index = "0", description = "First voucher ID")
    private String left;

    @CommandLine.Parameters(index = "1", description = "Second voucher ID")
    private String right;

    @Override
    public Integer call() {
        try (VoucherLedgerService service = buildService()) {
            Optional<VoucherNode> leftNode = service.fetchVoucher(left);
            Optional<VoucherNode> rightNode = service.fetchVoucher(right);
            if (leftNode.isEmpty() || rightNode.isEmpty()) {
                System.err.println("Vouchers not found: " + (leftNode.isEmpty() ? left : "") + " " + (rightNode.isEmpty() ? right : ""));
                return CommandLine.ExitCode.SOFTWARE;
            }
            DiffFormatter formatter = new DiffFormatter(parent.outputFormat());
            System.out.println(formatter.format(leftNode.get(), rightNode.get()));
            return CommandLine.ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Diff failed: " + e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    private VoucherLedgerService buildService() {
        int timeout = parent.timeoutSeconds();
        return new VoucherLedgerServiceImpl(
                new NostrRelayConnectionManager(),
                parent.relayUrls(),
                Duration.ofSeconds(timeout),
                Duration.ofSeconds(timeout)
        );
    }
}
