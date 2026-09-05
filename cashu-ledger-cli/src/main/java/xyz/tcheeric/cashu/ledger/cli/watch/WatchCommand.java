package xyz.tcheeric.cashu.ledger.cli.watch;

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
        name = "watch",
        description = "Watch a voucher for status changes (polling)"
)
public class WatchCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private CashuLedgerCommand parent;

    @CommandLine.Parameters(index = "0", description = "Voucher ID to watch")
    private String voucherId;

    @CommandLine.Option(names = {"--interval"}, description = "Polling interval in seconds", defaultValue = "5")
    private int intervalSeconds;

    @CommandLine.Option(names = {"--max-polls"}, description = "Maximum polls before exiting", defaultValue = "60")
    private int maxPolls;

    @Override
    @SuppressWarnings("BusyWait") // Intentional polling for CLI watch command
    public Integer call() {
        try (VoucherLedgerService service = buildService()) {
            Optional<VoucherNode> initial = service.fetchVoucher(voucherId);
            if (initial.isEmpty()) {
                System.err.println("Voucher not found: " + voucherId);
                return CommandLine.ExitCode.SOFTWARE;
            }
            String lastStatus = status(initial.get());
            System.out.println("Watching " + voucherId + " (initial status: " + lastStatus + ")");

            int polls = 0;
            while (polls < maxPolls) {
                //noinspection BusyWait - Intentional polling with configurable interval
                Thread.sleep(intervalSeconds * 1000L);
                polls++;
                Optional<VoucherNode> current = service.fetchVoucher(voucherId);
                if (current.isEmpty()) {
                    System.out.println("Voucher disappeared from relays");
                    break;
                }
                String nowStatus = status(current.get());
                if (!nowStatus.equals(lastStatus)) {
                    System.out.println("Status changed: " + lastStatus + " -> " + nowStatus);
                    lastStatus = nowStatus;
                }
            }
            return CommandLine.ExitCode.OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandLine.ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Watch failed: " + e.getMessage());
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

    private String status(VoucherNode node) {
        return node.status() == null ? "unknown" : node.status().name().toLowerCase();
    }
}
