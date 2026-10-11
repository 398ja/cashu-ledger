package xyz.tcheeric.cashu.ledger.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.trace.publisher.SignedTraceEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceEventSigner;

/**
 * Backfill/replay tool. Reads a newline-delimited JSON log of operations and emits a signed
 * kind-9079 event per line, each with a deterministic backfill operation id (design
 * FR-18/FR-19b). Re-running the same log yields byte-identical event ids, so publishing the
 * output is idempotent — the ledger dedups replays. The signed events are written to stdout
 * for the operator to publish through the normal relay path.
 */
@CommandLine.Command(name = "replay",
        description = "Re-sign a log of operations as deterministic backfill events")
public class ReplayCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private TraceCommand parent;

    @CommandLine.Parameters(index = "0", description = "Newline-delimited JSON operation log")
    private Path logFile;

    @CommandLine.Option(names = "--key", required = true,
            // See TraceCommand: a key on the command line is visible in `ps` and in shell
            // history (audit M-27).
            interactive = true, arity = "0..1",
            defaultValue = "${env:CASHU_LEDGER_OPERATOR_KEY}",
            description = "Producer private key (hex) used to sign replayed events")
    private String producerKey;

    @Override
    public Integer call() {
        TraceReplay replay = new TraceReplay(new TraceEventSigner(producerKey.trim()));
        ObjectMapper mapper = new ObjectMapper();
        int signed = 0;
        int failed = 0;
        try {
            List<String> lines = Files.readAllLines(logFile);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JsonNode operation = mapper.readTree(line);
                    SignedTraceEvent event = replay.toSignedEvent(operation);
                    System.out.println(event.eventJson());
                    signed++;
                } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                    failed++;
                    System.err.println("Skipped line " + (i + 1) + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to read " + logFile + ": " + e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
        System.err.println("replay_complete signed=" + signed + " failed=" + failed);
        return failed == 0 ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }
}
