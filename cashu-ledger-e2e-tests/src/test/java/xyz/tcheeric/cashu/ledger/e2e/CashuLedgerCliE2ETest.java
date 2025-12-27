package xyz.tcheeric.cashu.ledger.e2e;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.cli.CashuLedgerCommand;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures the CLI help runs end-to-end and lists the primary commands.
 */
class CashuLedgerCliE2ETest {

    @Test
    void shouldDisplayHelpWithAvailableCommands() {
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(capture));
        try {
            int exitCode = new CommandLine(new CashuLedgerCommand()).execute("--help");
            assertThat(exitCode).isZero();
            String output = capture.toString();
            assertThat(output).contains("inspect").contains("search").contains("verify");
        } finally {
            System.setOut(originalOut);
        }
    }
}
