package xyz.tcheeric.cashu.ledger.cli.inspect;

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
        name = "inspect",
        description = "Inspect a single voucher by ID"
)
public class InspectCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private CashuLedgerCommand parent;

    @CommandLine.Parameters(
            index = "0",
            description = "Voucher identifier (d-tag)"
    )
    private String voucherId;

    @Override
    public Integer call() {
        try (VoucherLedgerService service = buildService()) {
            Optional<VoucherNode> node = service.fetchVoucher(voucherId);
            if (node.isEmpty()) {
                System.err.println("Voucher not found: " + voucherId);
                return CommandLine.ExitCode.SOFTWARE;
            }

            InspectFormatter formatter = new InspectFormatter(parent.outputFormat());
            System.out.println(formatter.format(node.get()));
            return CommandLine.ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Inspect failed: " + e.getMessage());
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
