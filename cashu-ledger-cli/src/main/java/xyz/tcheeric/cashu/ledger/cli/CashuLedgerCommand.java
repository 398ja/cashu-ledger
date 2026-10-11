package xyz.tcheeric.cashu.ledger.cli;

import picocli.CommandLine;
import xyz.tcheeric.cashu.ledger.cli.inspect.InspectCommand;
import xyz.tcheeric.cashu.ledger.cli.tree.TreeCommand;
import xyz.tcheeric.cashu.ledger.cli.search.SearchCommand;
import xyz.tcheeric.cashu.ledger.cli.history.HistoryCommand;
import xyz.tcheeric.cashu.ledger.cli.export.ExportCommand;
import xyz.tcheeric.cashu.ledger.cli.unclaimed.UnclaimedCommand;
import xyz.tcheeric.cashu.ledger.cli.verify.VerifyCommand;
import xyz.tcheeric.cashu.ledger.cli.diff.DiffCommand;
import xyz.tcheeric.cashu.ledger.cli.watch.WatchCommand;
import xyz.tcheeric.cashu.ledger.cli.trace.TraceCommand;
import xyz.tcheeric.cashu.ledger.core.relay.CachingRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.relay.NostrRelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.relay.RelayConnectionManager;
import xyz.tcheeric.cashu.ledger.core.storage.EventStore;
import xyz.tcheeric.cashu.ledger.core.storage.EventStoreConfig;
import xyz.tcheeric.cashu.ledger.core.storage.NostrDbEventStore;

import java.nio.file.Path;
import java.time.Duration;
import xyz.tcheeric.cashu.ledger.core.trace.IssuerAttestationConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

@CommandLine.Command(
        name = "cashu-ledger",
        description = "Inspect and analyze vouchers stored on Nostr relays",
        mixinStandardHelpOptions = true,
        subcommands = {
                InspectCommand.class,
                TreeCommand.class,
                SearchCommand.class,
                HistoryCommand.class,
                ExportCommand.class,
                UnclaimedCommand.class,
                VerifyCommand.class,
                DiffCommand.class,
                WatchCommand.class,
                TraceCommand.class
        }
)
public class CashuLedgerCommand implements Runnable {

    @CommandLine.Option(
            names = {"-r", "--relay"},
            description = "Nostr relay URL (repeatable)",
            split = ","
    )
    private List<String> relayUrls = new ArrayList<>();

    @CommandLine.Option(
            names = {"--issuer-key"},
            description = "Trusted issuer key as <issuerId>=<hex pubkey> (repeatable). "
                    + "Without at least one, verify reports signatureValid=false for every "
                    + "voucher, because no key is trusted, and exits non-zero.",
            split = ","
    )
    private Map<String, String> issuerKeys = new LinkedHashMap<>();

    @CommandLine.Option(
            names = {"-t", "--timeout"},
            description = "Query timeout in seconds",
            defaultValue = "30"
    )
    private int timeoutSeconds;

    @CommandLine.Option(
            names = {"-o", "--output"},
            description = "Output format: text, json, tree",
            defaultValue = "text"
    )
    private String outputFormat;

    @CommandLine.Option(
            names = {"--storage-path"},
            description = "Path to local event store database"
    )
    private String storagePath;

    @CommandLine.Option(
            names = {"--no-cache"},
            description = "Disable local caching (relay-only mode)",
            defaultValue = "false"
    )
    private boolean noCache;

    private EventStore eventStore;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public List<String> relayUrls() {
        if (relayUrls == null || relayUrls.isEmpty()) {
            return List.of("wss://relay.imani.casa");
        }
        return relayUrls;
    }

    /**
     * The issuer keys verification will attest to.
     *
     * <p>Empty means nothing is trusted, so {@code verify} reports every signature untrusted and
     * exits non-zero. That default is fail-closed on purpose, but it is only defensible because
     * {@code --issuer-key} exists to change it.
     */
    public IssuerAttestationConfig issuerAttestation() {
        return new IssuerAttestationConfig(issuerKeys == null ? Map.of() : issuerKeys);
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public String outputFormat() {
        return outputFormat;
    }

    /**
     * Returns whether local caching is disabled.
     *
     * @return true if caching is disabled
     */
    public boolean isNoCache() {
        return noCache;
    }

    /**
     * Returns the storage path for the local event store.
     *
     * @return the storage path, or default if not specified
     */
    public String storagePath() {
        if (storagePath == null || storagePath.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".cashu-ledger", "ndb").toString();
        }
        return storagePath;
    }

    /**
     * Creates a relay connection manager, optionally with caching enabled.
     *
     * @return the relay connection manager
     */
    public RelayConnectionManager createRelayConnectionManager() {
        NostrRelayConnectionManager baseManager = new NostrRelayConnectionManager();

        if (noCache) {
            return baseManager;
        }

        EventStore store = getOrCreateEventStore();
        if (store != null && store.isAvailable()) {
            return new CachingRelayConnectionManager(baseManager, store, true);
        }

        return baseManager;
    }

    /**
     * Gets or creates the shared event store instance.
     *
     * @return the event store, or null if unavailable
     */
    public EventStore getOrCreateEventStore() {
        if (eventStore == null && !noCache) {
            EventStoreConfig config = new EventStoreConfig(
                    Path.of(storagePath()),
                    512L * 1024 * 1024, // 512MB
                    Duration.ofDays(30),
                    false
            );
            eventStore = new NostrDbEventStore(config);
        }
        return eventStore;
    }

    /**
     * Closes the event store if it was created.
     */
    public void closeEventStore() {
        if (eventStore != null) {
            eventStore.close();
            eventStore = null;
        }
    }
}
