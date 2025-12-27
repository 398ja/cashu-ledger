package xyz.tcheeric.cashu.ledger.cli.verify;

import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.cli.CashuLedgerCommand;
import xyz.tcheeric.cashu.ledger.core.relay.NostrRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerServiceImpl;
import xyz.tcheeric.cashu.ledger.core.state.VerificationReport;

import java.time.Duration;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "verify",
        description = "Verify voucher signature and value conservation"
)
public class VerifyCommand implements Callable<Integer> {

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
            VerificationReport report = service.verify(voucherId);
            VerifyFormatter formatter = new VerifyFormatter(parent.outputFormat());
            System.out.println(formatter.format(report));
            return report.found() && report.signatureValid() && report.valueConserved() && report.hierarchyComplete()
                    ? CommandLine.ExitCode.OK
                    : CommandLine.ExitCode.SOFTWARE;
        } catch (Exception e) {
            System.err.println("Verify failed: " + e.getMessage());
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
