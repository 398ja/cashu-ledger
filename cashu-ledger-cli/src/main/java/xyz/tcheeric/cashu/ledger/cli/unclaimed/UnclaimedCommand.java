package xyz.tcheeric.cashu.ledger.cli.unclaimed;

import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.cli.CashuLedgerCommand;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.relay.NostrRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerServiceImpl;
import xyz.tcheeric.cashu.ledger.core.state.ReclaimOutcome;
import xyz.tcheeric.cashu.ledger.core.state.UnclaimedStatusResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "unclaimed",
        description = "Manage unclaimed vouchers (list, check status, reclaim)"
)
public class UnclaimedCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private CashuLedgerCommand parent;

    @CommandLine.Option(
            names = {"--sent-by"},
            description = "Filter by sender pubkey (hex)"
    )
    private String sentBy;

    @CommandLine.Option(
            names = {"--limit"},
            description = "Maximum results when listing",
            defaultValue = "50"
    )
    private int limit;

    @CommandLine.Option(
            names = {"--check-status"},
            description = "Voucher ID to check reclaimability"
    )
    private String checkVoucherId;

    @CommandLine.Option(
            names = {"--reclaim"},
            description = "Voucher ID to reclaim"
    )
    private String reclaimVoucherId;

    @CommandLine.Option(
            names = {"--token-file"},
            description = "Path to original token file for reclaim"
    )
    private String tokenFile;

    @Override
    public Integer call() {
        try (VoucherLedgerService service = buildService()) {
            if (checkVoucherId != null && !checkVoucherId.isBlank()) {
                return handleCheck(service);
            }
            if (reclaimVoucherId != null && !reclaimVoucherId.isBlank()) {
                return handleReclaim(service);
            }
            return handleList(service);
        } catch (Exception e) {
            System.err.println("Unclaimed command failed: " + e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    private int handleList(VoucherLedgerService service) {
        List<VoucherNode> unclaimed = service.listUnclaimed(sentBy, limit);
        UnclaimedFormatter formatter = new UnclaimedFormatter(parent.outputFormat());
        System.out.println(formatter.formatList(unclaimed));
        return CommandLine.ExitCode.OK;
    }

    private int handleCheck(VoucherLedgerService service) {
        UnclaimedStatusResult result = service.checkUnclaimedStatus(checkVoucherId, tokenFile);
        UnclaimedFormatter formatter = new UnclaimedFormatter(parent.outputFormat());
        System.out.println(formatter.formatCheck(result));
        return result.reclaimable() ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }

    private int handleReclaim(VoucherLedgerService service) {
        ReclaimOutcome outcome = service.reclaim(reclaimVoucherId, tokenFile);
        UnclaimedFormatter formatter = new UnclaimedFormatter(parent.outputFormat());
        System.out.println(formatter.formatReclaim(outcome));
        return outcome.success() ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
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
}
