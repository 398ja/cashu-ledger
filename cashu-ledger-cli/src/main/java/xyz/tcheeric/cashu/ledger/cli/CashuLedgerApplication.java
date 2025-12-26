package xyz.tcheeric.cashu.ledger.cli;

import picocli.CommandLine;

public final class CashuLedgerApplication {

    private CashuLedgerApplication() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CashuLedgerCommand()).execute(args);
        System.exit(exitCode);
    }
}
