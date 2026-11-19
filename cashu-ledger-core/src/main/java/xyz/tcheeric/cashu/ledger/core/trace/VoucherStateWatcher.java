package xyz.tcheeric.cashu.ledger.core.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import nostr.client.springwebsocket.NostrRelayClient;
import nostr.crypto.schnorr.Schnorr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;

/**
 * Subscribes to kind-30078 voucher-state events on the configured relays, reduces each to a {@link
 * VoucherStatusObservation}, and records the latest revision in the sidecar. When a voucher first
 * reaches a terminal status it notifies the {@link VoucherTerminalListener} so dependent caches can
 * invalidate. The last-seen {@code created_at} per relay is persisted as a resumable cursor, and
 * {@link #lagSeconds(Instant)} reports ingest lag (design §5.4.1, modelled on {@link
 * TraceSyncEngine}).
 *
 * <h2>Trust (cashu-ledger#17)</h2>
 *
 * <p>The subscription is public, so anyone can publish a kind-30078 event naming any voucher. A
 * terminal status flips the voucher's trace events to terminal, and retention then prunes them
 * early, so an unverified status could destroy evidence. Every event is therefore checked before
 * it is recorded: the id is recomputed from the NIP-01 serialisation, the BIP-340 signature is
 * verified against the event's {@code pubkey}, and that pubkey must be authorised for the voucher
 * by {@link VoucherStatusAuthority} (the issuer's registered key or a configured gateway signer).
 * The self-asserted {@code issuer_pubkey} tag is never used for authorisation. A {@code
 * state_version} that jumps more than {@link #DEFAULT_MAX_VERSION_JUMP} past the recorded one is
 * refused so a single event cannot pin the state forever. Dropped events are counted by reason and
 * logged; nothing they carry reaches the sidecar or the terminal listener. Two verified terminal
 * statuses that disagree raise {@code voucher_terminal_conflict} (a possible double spend).
 */
public final class VoucherStateWatcher implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(VoucherStateWatcher.class);
  static final int VOUCHER_KIND = 30078;
  private static final String D_TAG_PREFIX = "voucher:";
  private static final String CURSOR_PREFIX = "voucher_watcher:";
  private static final HexFormat HEX = HexFormat.of();

  /** The largest forward {@code state_version} step accepted from one event. */
  public static final long DEFAULT_MAX_VERSION_JUMP = 1000;

  private final SqliteSidecarIndex index;
  private final VoucherTerminalListener terminalListener;
  private final VoucherStatusAuthority authority;
  private final VoucherStatusMetricsRecorder metrics;
  private final long maxVersionJump;
  private final ObjectMapper mapper = new ObjectMapper();
  private final List<AutoCloseable> subscriptions = new ArrayList<>();
  private final List<NostrRelayClient> clients = new ArrayList<>();
  private final ReentrantLock lock = new ReentrantLock();
  private final AtomicLong lastCreatedAtS = new AtomicLong(0);

  public VoucherStateWatcher(
      SqliteSidecarIndex index,
      VoucherTerminalListener terminalListener,
      VoucherStatusAuthority authority,
      VoucherStatusMetricsRecorder metrics) {
    this(index, terminalListener, authority, metrics, DEFAULT_MAX_VERSION_JUMP);
  }

  public VoucherStateWatcher(
      SqliteSidecarIndex index,
      VoucherTerminalListener terminalListener,
      VoucherStatusAuthority authority,
      VoucherStatusMetricsRecorder metrics,
      long maxVersionJump) {
    this.index = index;
    this.terminalListener = terminalListener;
    this.authority = authority != null ? authority : VoucherStatusAuthority.none();
    this.metrics = metrics != null ? metrics : VoucherStatusMetricsRecorder.NOOP;
    this.maxVersionJump = maxVersionJump;
    if (this.authority.isEmpty()) {
      LOGGER.warn(
          "voucher_watch_no_trust_anchor: no issuer keys or gateway status signers configured,"
              + " every voucher-status event will be dropped");
    }
  }

  /** Opens a long-lived kind-30078 subscription to {@code relayUrl}, resuming from its cursor. */
  public void subscribe(String relayUrl) {
    lock.lock();
    try {
      NostrRelayClient client = new NostrRelayClient(relayUrl);
      String subscriptionId = "voucher-" + UUID.randomUUID().toString().substring(0, 8);
      long since = index.loadCursor(CURSOR_PREFIX + relayUrl).orElse(0L);
      String filter =
          since > 0
              ? "{\"kinds\":[" + VOUCHER_KIND + "],\"since\":" + since + "}"
              : "{\"kinds\":[" + VOUCHER_KIND + "]}";
      String req = "[\"REQ\",\"" + subscriptionId + "\"," + filter + "]";

      AutoCloseable subscription =
          client.subscribe(
              req,
              message -> handleMessage(message, relayUrl),
              error ->
                  LOGGER.warn(
                      "voucher_watch_error relay={} subscription={} error={}",
                      relayUrl,
                      subscriptionId,
                      error.getMessage()),
              () ->
                  LOGGER.debug(
                      "voucher_watch_eose relay={} subscription={}", relayUrl, subscriptionId));

      clients.add(client);
      subscriptions.add(subscription);
      LOGGER.info(
          "voucher_watch_subscribed relay={} subscription={} since={}",
          relayUrl,
          subscriptionId,
          since);
    } catch (Exception e) {
      throw new TraceStorageException("Failed to subscribe voucher watcher to " + relayUrl, e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Handles one raw {@code ["EVENT", subId, {event}]} message, recording the voucher status and
   * advancing the relay cursor. Package-visible for tests.
   */
  void handleMessage(String message, String relayUrl) {
    try {
      JsonNode root = mapper.readTree(message);
      if (!root.isArray() || root.size() < 3 || !"EVENT".equals(root.get(0).asText())) {
        return;
      }
      JsonNode event = root.get(2);
      long createdAt = event.path("created_at").asLong(0);
      if (event.path("kind").asInt(-1) == VOUCHER_KIND) {
        Optional<String> rejection = verify(event);
        if (rejection.isPresent()) {
          reject(rejection.get(), event, relayUrl);
        } else {
          parse(event).ifPresent(obs -> accept(obs, event, relayUrl));
        }
      }
      advanceCursor(relayUrl, createdAt);
    } catch (RuntimeException e) {
      LOGGER.warn("voucher_watch_message_failed relay={} error={}", relayUrl, e.getMessage());
    } catch (Exception e) {
      LOGGER.warn("voucher_watch_message_unreadable relay={} error={}", relayUrl, e.getMessage());
    }
  }

  /**
   * Checks the id, signature and author of a kind-30078 event. Returns the rejection code, or empty
   * if the event is authentic and its signer may publish status for the voucher.
   */
  Optional<String> verify(JsonNode event) {
    String id = event.path("id").asText(null);
    String pubkey = event.path("pubkey").asText(null);
    String sig = event.path("sig").asText(null);
    if (isBlank(id) || isBlank(pubkey) || isBlank(sig)) {
      return Optional.of("UNSIGNED");
    }
    if (!event.path("created_at").canConvertToLong() || !event.path("tags").isArray()) {
      return Optional.of("MALFORMED");
    }
    List<List<String>> tags = new ArrayList<>();
    for (JsonNode tag : event.path("tags")) {
      if (!tag.isArray()) {
        return Optional.of("MALFORMED");
      }
      List<String> values = new ArrayList<>();
      for (JsonNode v : tag) {
        if (!v.isTextual()) {
          return Optional.of("MALFORMED");
        }
        values.add(v.asText());
      }
      tags.add(values);
    }
    JsonNode contentNode = event.path("content");
    String content = contentNode.isTextual() ? contentNode.asText() : "";
    String expectedId =
        CanonicalJson.eventId(
            pubkey, event.path("created_at").asLong(), VOUCHER_KIND, tags, content);
    if (!expectedId.equalsIgnoreCase(id)) {
      return Optional.of("INVALID_ID");
    }
    try {
      if (!Schnorr.verify(HEX.parseHex(id), HEX.parseHex(pubkey), HEX.parseHex(sig))) {
        return Optional.of("INVALID_SIGNATURE");
      }
    } catch (Exception e) {
      return Optional.of("INVALID_SIGNATURE");
    }
    if (!authority.isAuthorised(tagValue(event, "issuer_id"), pubkey)) {
      return Optional.of("UNAUTHORISED_AUTHOR");
    }
    return Optional.empty();
  }

  private void accept(VoucherStatusObservation obs, JsonNode event, String relayUrl) {
    Optional<VoucherStatusObservation> existing = index.loadVoucherStatus(obs.voucherId());
    if (existing.isPresent()
        && obs.stateVersion() > existing.get().stateVersion()
        && obs.stateVersion() - existing.get().stateVersion() > maxVersionJump) {
      reject("VERSION_JUMP", event, relayUrl);
      return;
    }
    if (existing.isEmpty() && obs.stateVersion() > maxVersionJump) {
      reject("VERSION_JUMP", event, relayUrl);
      return;
    }
    existing
        .filter(VoucherStatusObservation::terminal)
        .filter(prior -> obs.terminal() && !prior.status().equals(obs.status()))
        .ifPresent(prior -> terminalConflict(prior, obs, event));
    metrics.recordAccepted();
    record(obs);
  }

  private void terminalConflict(
      VoucherStatusObservation prior, VoucherStatusObservation obs, JsonNode event) {
    metrics.recordTerminalConflict();
    LOGGER.error(
        "voucher_terminal_conflict voucher_id={} recorded_status={} recorded_version={}"
            + " new_status={} new_version={} event_id={} signer={} (possible double spend)",
        obs.voucherId(),
        prior.status(),
        prior.stateVersion(),
        obs.status(),
        obs.stateVersion(),
        event.path("id").asText("?"),
        event.path("pubkey").asText("?"));
  }

  private void reject(String code, JsonNode event, String relayUrl) {
    metrics.recordRejected(code);
    LOGGER.warn(
        "voucher_status_rejected code={} relay={} event_id={} pubkey={} d={}",
        code,
        relayUrl,
        event.path("id").asText("?"),
        event.path("pubkey").asText("?"),
        tagValue(event, "d"));
  }

  private static String tagValue(JsonNode event, String name) {
    for (JsonNode tag : event.path("tags")) {
      if (tag.isArray() && tag.size() > 1 && name.equals(tag.get(0).asText())) {
        return tag.get(1).asText();
      }
    }
    return null;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Reduces a kind-30078 event node to an observation, or empty if it is not one. */
  Optional<VoucherStatusObservation> parse(JsonNode event) {
    if (event.path("kind").asInt(-1) != VOUCHER_KIND) {
      return Optional.empty();
    }
    String voucherId = null, status = null, issuerId = null, issuerPubkey = null;
    long stateVersion = 0;
    Long transitionAt = null;
    for (JsonNode tag : event.path("tags")) {
      if (!tag.isArray() || tag.isEmpty()) {
        continue;
      }
      String code = tag.get(0).asText();
      String value = tag.size() > 1 ? tag.get(1).asText() : null;
      switch (code) {
        case "d" -> voucherId = stripPrefix(value);
        case "status" -> status = value;
        case "state_version" -> stateVersion = parseLong(value);
        case "transition_at" -> transitionAt = value != null ? Long.parseLong(value) : null;
        case "issuer_id" -> issuerId = value;
        case "issuer_pubkey" -> {} // self-asserted; the verified signer is used instead
        default -> {}
      }
    }
    if (voucherId == null || voucherId.isBlank()) {
      return Optional.empty();
    }
    String signer = event.path("pubkey").asText(null);
    if (signer != null && !authority.isGatewaySigner(signer)) {
      issuerPubkey = signer.toLowerCase(java.util.Locale.ROOT); // verified issuer key
    }
    VoucherStatus parsed = VoucherStatus.fromValue(status);
    Instant at =
        Instant.ofEpochSecond(
            transitionAt != null ? transitionAt : event.path("created_at").asLong(0));
    return Optional.of(
        new VoucherStatusObservation(
            voucherId,
            parsed.name().toLowerCase(),
            stateVersion,
            at,
            parsed.isTerminal(),
            Optional.ofNullable(issuerId),
            Optional.ofNullable(issuerPubkey)));
  }

  /**
   * Records an already-verified observation, notifying the listener when it first turns terminal.
   * Returns {@code true} if this observation caused the terminal transition. Relay input must go
   * through {@link #handleMessage}, which verifies first.
   */
  boolean record(VoucherStatusObservation observation) {
    boolean newlyTerminal = index.upsertVoucherStatus(observation);
    if (newlyTerminal) {
      LOGGER.info(
          "voucher_terminal voucher_id={} status={} state_version={}",
          observation.voucherId(),
          observation.status(),
          observation.stateVersion());
      terminalListener.onVoucherTerminal(observation);
    }
    return newlyTerminal;
  }

  /** Seconds between {@code now} and the newest voucher event seen, or 0 if none. */
  public long lagSeconds(Instant now) {
    long latest = lastCreatedAtS.get();
    return latest == 0 ? 0 : Math.max(0, now.getEpochSecond() - latest);
  }

  private void advanceCursor(String relayUrl, long createdAt) {
    if (createdAt <= 0) {
      return;
    }
    lastCreatedAtS.accumulateAndGet(createdAt, Math::max);
    index.saveCursor(CURSOR_PREFIX + relayUrl, createdAt);
  }

  private static String stripPrefix(String dTag) {
    if (dTag == null) {
      return null;
    }
    return dTag.startsWith(D_TAG_PREFIX) ? dTag.substring(D_TAG_PREFIX.length()) : dTag;
  }

  private static long parseLong(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  @Override
  public void close() {
    lock.lock();
    try {
      subscriptions.forEach(VoucherStateWatcher::closeQuietly);
      clients.forEach(VoucherStateWatcher::closeQuietly);
      subscriptions.clear();
      clients.clear();
    } finally {
      lock.unlock();
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception e) {
      LOGGER.debug("voucher_watch_close_failed error={}", e.getMessage());
    }
  }
}
