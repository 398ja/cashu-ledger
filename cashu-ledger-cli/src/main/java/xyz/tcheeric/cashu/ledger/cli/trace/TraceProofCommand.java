package xyz.tcheeric.cashu.ledger.cli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;

/**
 * Looks up a proof's history (or walks its chain of custody) by its {@code Y} value. The
 * {@code Y} can be supplied directly with {@code --y}, or derived locally from a raw
 * {@code --secret} via {@code hash_to_curve} so the secret never leaves the machine
 * (design §5.5 / FR-003, FR-010). With {@code --walk} the proof's graph is fetched and, in
 * {@code --output tree} mode, drawn as an ASCII DAG.
 */
@CommandLine.Command(name = "proof", description = "Inspect a proof's history or chain of custody by Y")
public class TraceProofCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private TraceCommand parent;

    @CommandLine.Option(names = "--secret", description = "Raw proof secret (Y derived locally; never sent)")
    private String secret;

    @CommandLine.Option(names = "--y", description = "Proof Y value (hex) when known")
    private String y;

    @CommandLine.Option(names = "--mint", description = "Mint URL to disambiguate a shared Y")
    private String mintUrl;

    @CommandLine.Option(names = "--keyset", description = "Keyset id to disambiguate a shared Y")
    private String keysetId;

    @CommandLine.Option(names = "--walk", description = "Walk the proof's chain of custody")
    private boolean walk;

    @CommandLine.Option(names = "--direction", description = "Walk direction: up, down, both",
            defaultValue = "both")
    private String direction;

    @CommandLine.Option(names = "--depth", description = "Walk depth", defaultValue = "10")
    private int depth;

    @Override
    public Integer call() {
        String yValue;
        try {
            yValue = resolveY();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return CommandLine.ExitCode.USAGE;
        }

        try {
            JsonNode result = parent.client().get(buildPath(yValue));
            if (result == null) {
                System.err.println("No trace records found for Y " + yValue);
                return CommandLine.ExitCode.SOFTWARE;
            }
            System.out.println(format(result));
            return CommandLine.ExitCode.OK;
        } catch (TraceApiException e) {
            System.err.println(e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    private String resolveY() {
        if (y != null && !y.isBlank()) {
            return y.trim();
        }
        if (secret != null && !secret.isBlank()) {
            // hash_to_curve over the raw secret bytes; only the resulting Y is ever sent.
            return BDHKEUtils.pointToHex(
                    BDHKEUtils.hashToCurve(secret.getBytes(StandardCharsets.UTF_8)));
        }
        throw new IllegalArgumentException("Provide either --y or --secret to identify the proof.");
    }

    private String buildPath(String yValue) {
        StringBuilder path = new StringBuilder("/trace/proofs/").append(enc(yValue));
        if (walk) {
            path.append("/walk?direction=").append(enc(direction)).append("&depth=").append(depth);
            if (mintUrl != null) {
                path.append("&mintUrl=").append(enc(mintUrl));
            }
            if (keysetId != null) {
                path.append("&keysetId=").append(enc(keysetId));
            }
            return path.toString();
        }
        if (mintUrl != null || keysetId != null) {
            path.append('?');
            if (mintUrl != null) {
                path.append("mintUrl=").append(enc(mintUrl));
            }
            if (keysetId != null) {
                path.append(mintUrl != null ? "&" : "").append("keysetId=").append(enc(keysetId));
            }
        }
        return path.toString();
    }

    private String format(JsonNode result) {
        if (walk && "tree".equalsIgnoreCase(parent.output())) {
            return new AsciiDagRenderer().render(result);
        }
        return result.toPrettyString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
