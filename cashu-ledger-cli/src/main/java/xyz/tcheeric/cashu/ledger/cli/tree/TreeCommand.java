package xyz.tcheeric.cashu.ledger.cli.tree;

import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.cli.CashuLedgerCommand;
import xyz.tcheeric.cashu.ledger.core.model.TraversalDirection;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;
import xyz.tcheeric.cashu.ledger.core.relay.NostrRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerServiceImpl;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "tree",
        description = "Display voucher hierarchy (ancestors/descendants)"
)
public class TreeCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private CashuLedgerCommand parent;

    @CommandLine.Parameters(
            index = "0",
            description = "Voucher identifier (d-tag)"
    )
    private String voucherId;

    @CommandLine.Option(
            names = {"--depth"},
            description = "Maximum traversal depth",
            defaultValue = "10"
    )
    private int depth;

    @CommandLine.Option(
            names = {"--direction"},
            description = "Traversal direction: up, down, both",
            defaultValue = "both"
    )
    private String direction;

    @Override
    public Integer call() {
        try (VoucherLedgerService service = buildService()) {
            TraversalDirection traversalDirection = parseDirection(direction);
            Optional<VoucherTree> tree = service.buildTree(voucherId, depth, traversalDirection);
            if (tree.isEmpty()) {
                System.err.println("Voucher not found: " + voucherId);
                return CommandLine.ExitCode.SOFTWARE;
            }
            TreeFormatter formatter = new TreeFormatter(parent.outputFormat());
            System.out.println(formatter.format(tree.get()));
            return CommandLine.ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Tree command failed: " + e.getMessage());
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

    private TraversalDirection parseDirection(String value) {
        if (value == null) {
            return TraversalDirection.BOTH;
        }
        return switch (value.toLowerCase()) {
            case "up" -> TraversalDirection.UP;
            case "down" -> TraversalDirection.DOWN;
            default -> TraversalDirection.BOTH;
        };
    }
}
