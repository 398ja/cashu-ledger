package xyz.tcheeric.cashu.ledger.cli.search;

import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.cli.CashuLedgerCommand;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;
import xyz.tcheeric.cashu.ledger.core.relay.NostrRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerServiceImpl;
import xyz.tcheeric.cashu.ledger.core.state.VoucherSearchCriteria;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "search",
        description = "Search vouchers by issuer, status, and time range"
)
public class SearchCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private CashuLedgerCommand parent;

    @CommandLine.Option(
            names = {"--issuer"},
            description = "Issuer identifier"
    )
    private String issuerId;

    @CommandLine.Option(
            names = {"--status"},
            description = "Voucher status (issued, claimed, split, redeemed, reclaimed, revoked, expired)"
    )
    private String status;

    @CommandLine.Option(
            names = {"--since"},
            description = "Only include vouchers issued after this ISO-8601 timestamp"
    )
    private String since;

    @CommandLine.Option(
            names = {"--until"},
            description = "Only include vouchers issued before this ISO-8601 timestamp"
    )
    private String until;

    @CommandLine.Option(
            names = {"--limit"},
            description = "Maximum results",
            defaultValue = "50"
    )
    private int limit;

    @CommandLine.Option(
            names = {"--unclaimed"},
            description = "Only vouchers in ISSUED state"
    )
    private boolean unclaimed;

    @Override
    public Integer call() {
        try (VoucherLedgerService service = buildService()) {
            VoucherSearchCriteria criteria = new VoucherSearchCriteria(
                    issuerId,
                    parseStatus(status),
                    parseInstant(since),
                    parseInstant(until),
                    limit,
                    unclaimed
            );
            List<VoucherNode> results = service.search(criteria);
            SearchFormatter formatter = new SearchFormatter(parent.outputFormat());
            System.out.println(formatter.format(results));
            return CommandLine.ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Search failed: " + e.getMessage());
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

    private VoucherStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return VoucherStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}
