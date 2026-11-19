package xyz.tcheeric.cashu.ledger.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Unit tests for {@link VoucherStateWatcher}: parsing kind-30078 events, recording the latest
 * revision, firing the terminal listener once, advancing the relay cursor, reporting ingest lag,
 * and (cashu-ledger#17) refusing unsigned, forged and unauthorised status events, bounding {@code
 * state_version} jumps, keeping retention away from vouchers whose only terminal signal was
 * refused, and alerting on conflicting verified terminal statuses.
 */
class VoucherStateWatcherTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final String RELAY = "wss://relay.imani.casa";
  private static final String ISSUER = "iss-1";
  private static final String ISSUER_PRIV =
      "0000000000000000000000000000000000000000000000000000000000000003";
  private static final String GATEWAY_PRIV =
      "0000000000000000000000000000000000000000000000000000000000000005";
  private static final String ATTACKER_PRIV =
      "0000000000000000000000000000000000000000000000000000000000000007";
  private static final String ISSUER_PUB = pub(ISSUER_PRIV);
  private static final String GATEWAY_PUB = pub(GATEWAY_PRIV);
  private static final String ATTACKER_PUB = pub(ATTACKER_PRIV);

  private SqliteSidecarIndex index;
  private List<VoucherStatusObservation> terminalEvents;
  private RecordingMetrics metrics;
  private VoucherStateWatcher watcher;

  @BeforeEach
  void setUp() {
    index = new SqliteSidecarIndex("jdbc:sqlite::memory:");
    terminalEvents = new ArrayList<>();
    metrics = new RecordingMetrics();
    VoucherStatusAuthority authority =
        new VoucherStatusAuthority(
            new IssuerAttestationConfig(Map.of(ISSUER, ISSUER_PUB)), Set.of(GATEWAY_PUB));
    watcher = new VoucherStateWatcher(index, terminalEvents::add, authority, metrics);
  }

  @AfterEach
  void tearDown() {
    index.close();
  }

  /** A non-terminal status is recorded without firing the terminal listener. */
  @Test
  void shouldRecordNonTerminalStatusWithoutNotifying() {
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "claimed", 1, 1_700_000_000L), RELAY);

    assertThat(terminalEvents).isEmpty();
    assertThat(index.loadVoucherStatus("v1"))
        .get()
        .extracting(VoucherStatusObservation::status)
        .isEqualTo("claimed");
    assertThat(metrics.accepted).isEqualTo(1);
  }

  /** A terminal status fires the listener exactly once on first arrival. */
  @Test
  void shouldNotifyOnceWhenVoucherFirstBecomesTerminal() {
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "claimed", 1, 1_700_000_000L), RELAY);

    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "redeemed", 2, 1_700_000_100L), RELAY);
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "redeemed", 2, 1_700_000_100L), RELAY);

    assertThat(terminalEvents).hasSize(1);
    assertThat(terminalEvents.get(0).voucherId()).isEqualTo("v1");
    assertThat(terminalEvents.get(0).terminal()).isTrue();
  }

  /** A stale revision (lower state_version) does not overwrite the latest status. */
  @Test
  void shouldIgnoreStaleRevision() {
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "redeemed", 2, 1_700_000_100L), RELAY);

    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "claimed", 1, 1_700_000_000L), RELAY);

    assertThat(index.loadVoucherStatus("v1"))
        .get()
        .extracting(VoucherStatusObservation::status)
        .isEqualTo("redeemed");
  }

  /** The relay cursor advances to the newest created_at seen. */
  @Test
  void shouldAdvanceCursorToNewestCreatedAt() {
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "issued", 1, 1_700_000_000L), RELAY);
    watcher.handleMessage(signed(ISSUER_PRIV, "v2", "issued", 1, 1_700_000_500L), RELAY);

    assertThat(index.loadCursor("voucher_watcher:" + RELAY)).contains(1_700_000_500L);
  }

  /** Lag is the gap between now and the newest event's created_at. */
  @Test
  void shouldReportLagFromNewestEvent() {
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "issued", 1, 1_700_000_000L), RELAY);

    assertThat(watcher.lagSeconds(Instant.ofEpochSecond(1_700_000_060L))).isEqualTo(60);
  }

  /** A status signed by the configured gateway key is accepted for any voucher. */
  @Test
  void shouldAcceptStatusFromGatewaySigner() {
    watcher.handleMessage(signed(GATEWAY_PRIV, "v1", "redeemed", 1, 1_700_000_000L), RELAY);

    assertThat(terminalEvents).hasSize(1);
    assertThat(index.loadVoucherStatus("v1")).isPresent();
  }

  /** The issuer_pubkey recorded is the verified signer, never the self-asserted tag. */
  @Test
  void shouldRecordVerifiedSignerAsIssuerPubkey() {
    watcher.handleMessage(
        signed(ISSUER_PRIV, "v1", "issued", 1, 1_700_000_000L, ISSUER, ATTACKER_PUB, null), RELAY);

    assertThat(index.loadVoucherStatus("v1"))
        .get()
        .extracting(VoucherStatusObservation::issuerPubkey)
        .isEqualTo(Optional.of(ISSUER_PUB));
  }

  /** An unsigned event (no id, pubkey or sig) is refused and counted. */
  @Test
  void shouldRefuseUnsignedEvent() {
    watcher.handleMessage(unsigned("v1", "redeemed", 2, 1_700_000_000L), RELAY);

    assertThat(index.loadVoucherStatus("v1")).isEmpty();
    assertThat(terminalEvents).isEmpty();
    assertThat(metrics.rejected).containsExactly("UNSIGNED");
  }

  /** A signature that does not verify is refused and counted. */
  @Test
  void shouldRefuseForgedSignature() throws Exception {
    ObjectNode ev = signedNode(ISSUER_PRIV, "v1", "redeemed", 2, 1_700_000_000L, ISSUER, null);
    // Sign a different id with the issuer's key: a well-formed but wrong signature.
    byte[] wrongSig =
        Schnorr.sign(new byte[32], HEX.parseHex(ISSUER_PRIV), new byte[32]);
    ev.put("sig", HEX.formatHex(wrongSig));

    watcher.handleMessage(frame(ev), RELAY);

    assertThat(index.loadVoucherStatus("v1")).isEmpty();
    assertThat(terminalEvents).isEmpty();
    assertThat(metrics.rejected).containsExactly("INVALID_SIGNATURE");
  }

  /** Tags altered after signing break the id and are refused. */
  @Test
  void shouldRefuseTamperedEvent() {
    ObjectNode ev = signedNode(ISSUER_PRIV, "v1", "claimed", 1, 1_700_000_000L, ISSUER, null);
    ((ArrayNode) ev.get("tags").get(1)).set(1, ev.textNode("redeemed"));

    watcher.handleMessage(frame(ev), RELAY);

    assertThat(index.loadVoucherStatus("v1")).isEmpty();
    assertThat(metrics.rejected).containsExactly("INVALID_ID");
  }

  /** A validly signed event from a key that is not the voucher's issuer is refused. */
  @Test
  void shouldRefuseValidSignatureFromWrongAuthor() {
    // The attacker even claims the issuer's pubkey in the tag; that must not matter.
    watcher.handleMessage(
        signed(ATTACKER_PRIV, "v1", "redeemed", 2, 1_700_000_000L, ISSUER, ISSUER_PUB, null),
        RELAY);

    assertThat(index.loadVoucherStatus("v1")).isEmpty();
    assertThat(terminalEvents).isEmpty();
    assertThat(metrics.rejected).containsExactly("UNAUTHORISED_AUTHOR");
  }

  /** Signing as issuer A does not authorise status for a voucher of issuer B. */
  @Test
  void shouldRefuseIssuerKeyForAnotherIssuer() {
    watcher.handleMessage(
        signed(ISSUER_PRIV, "v1", "redeemed", 2, 1_700_000_000L, "iss-other", null, null), RELAY);

    assertThat(index.loadVoucherStatus("v1")).isEmpty();
    assertThat(metrics.rejected).containsExactly("UNAUTHORISED_AUTHOR");
  }

  /** A forged Long.MAX_VALUE state_version does not pin the state; genuine updates still apply. */
  @Test
  void shouldNotLetForgedMaxVersionPinState() {
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "claimed", 1, 1_700_000_000L), RELAY);

    watcher.handleMessage(
        signed(ATTACKER_PRIV, "v1", "redeemed", Long.MAX_VALUE, 1_700_000_050L), RELAY);
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "redeemed", 2, 1_700_000_100L), RELAY);

    VoucherStatusObservation latest = index.loadVoucherStatus("v1").orElseThrow();
    assertThat(latest.stateVersion()).isEqualTo(2);
    assertThat(latest.status()).isEqualTo("redeemed");
  }

  /** Even an authorised signer cannot jump state_version by more than the bound. */
  @Test
  void shouldRefuseUnboundedVersionJumpFromAuthorisedSigner() {
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "claimed", 1, 1_700_000_000L), RELAY);

    watcher.handleMessage(
        signed(ISSUER_PRIV, "v1", "redeemed", Long.MAX_VALUE, 1_700_000_050L), RELAY);

    assertThat(index.loadVoucherStatus("v1").orElseThrow().stateVersion()).isEqualTo(1);
    assertThat(metrics.rejected).containsExactly("VERSION_JUMP");
  }

  /** Two verified terminal statuses that disagree raise the conflict alert. */
  @Test
  void shouldAlertOnConflictingVerifiedTerminalStatuses() {
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "redeemed", 2, 1_700_000_000L), RELAY);

    watcher.handleMessage(signed(GATEWAY_PRIV, "v1", "revoked", 3, 1_700_000_100L), RELAY);

    assertThat(metrics.terminalConflicts).isEqualTo(1);
  }

  /** A replay of the same terminal status is not a conflict. */
  @Test
  void shouldNotAlertOnRepeatedSameTerminalStatus() {
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "redeemed", 2, 1_700_000_000L), RELAY);
    watcher.handleMessage(signed(GATEWAY_PRIV, "v1", "redeemed", 3, 1_700_000_100L), RELAY);

    assertThat(metrics.terminalConflicts).isZero();
  }

  /** A later non-terminal revision from an authorised signer cannot re-open a terminal voucher. */
  @Test
  void shouldNotRollTerminalStatusBack() {
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "redeemed", 2, 1_700_000_000L), RELAY);

    watcher.handleMessage(signed(GATEWAY_PRIV, "v1", "claimed", 3, 1_700_000_100L), RELAY);

    assertThat(index.loadVoucherStatus("v1").orElseThrow().status()).isEqualTo("redeemed");
    assertThat(metrics.rejected).containsExactly("TERMINAL_ROLLBACK");
  }

  /** Another registered issuer cannot take over a voucher already bound to a different issuer. */
  @Test
  void shouldRefuseStatusFromAnotherIssuerForBoundVoucher() {
    String otherPriv = "0000000000000000000000000000000000000000000000000000000000000009";
    VoucherStatusAuthority authority =
        new VoucherStatusAuthority(
            new IssuerAttestationConfig(Map.of(ISSUER, ISSUER_PUB, "iss-2", pub(otherPriv))),
            Set.of());
    watcher = new VoucherStateWatcher(index, terminalEvents::add, authority, metrics);
    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "claimed", 1, 1_700_000_000L), RELAY);

    watcher.handleMessage(
        signed(otherPriv, "v1", "redeemed", 2, 1_700_000_100L, "iss-2", null, null), RELAY);

    assertThat(index.loadVoucherStatus("v1").orElseThrow().status()).isEqualTo("claimed");
    assertThat(terminalEvents).isEmpty();
    assertThat(metrics.rejected).containsExactly("ISSUER_MISMATCH");
  }

  /** With no trust anchor every event is dropped under its own, alertable reason. */
  @Test
  void shouldCountDropsWhenNoTrustAnchorConfigured() {
    watcher =
        new VoucherStateWatcher(index, terminalEvents::add, VoucherStatusAuthority.none(), metrics);

    watcher.handleMessage(signed(ISSUER_PRIV, "v1", "redeemed", 1, 1_700_000_000L), RELAY);

    assertThat(index.loadVoucherStatus("v1")).isEmpty();
    assertThat(metrics.rejected).containsExactly("NO_TRUST_ANCHOR");
  }

  /** Retention does not prune a voucher whose only terminal signal was refused. */
  @Test
  void shouldNotPruneVoucherWhoseOnlyTerminalSignalWasRejected() {
    // Given: a real watcher feeding the activity cache, and a SEND bound to v1 two days old
    Instant now = Instant.parse("2026-06-20T00:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    IndexedTraceEventStore store = new IndexedTraceEventStore(new InMemoryRawEventStore(), index);
    store.store(StoredEvent.of(send("e-send", "v1", now.minus(Duration.ofDays(2)))));
    ActivityCache cache = new ActivityCache(index, clock);
    VoucherStateWatcher wired =
        new VoucherStateWatcher(
            index,
            cache,
            new VoucherStatusAuthority(
                new IssuerAttestationConfig(Map.of(ISSUER, ISSUER_PUB)), Set.of()),
            metrics);

    // When: forged, unsigned and wrong-author terminal statuses arrive, then retention runs
    wired.handleMessage(unsigned("v1", "redeemed", 2, 1_700_000_000L), RELAY);
    wired.handleMessage(signed(ATTACKER_PRIV, "v1", "redeemed", 2, 1_700_000_000L), RELAY);
    RetentionEngine engine =
        new RetentionEngine(
            store,
            index,
            new TombstoneStore(store),
            Duration.ofDays(30),
            Duration.ofDays(1),
            true,
            () -> false,
            100,
            clock);
    PruneResult result = engine.runOnce();

    // Then: nothing turned terminal and nothing was pruned
    assertThat(result.terminalPruned()).isZero();
    assertThat(index.isTombstoned("e-send")).isFalse();
    assertThat(index.activityOf("e-send").orElseThrow().isTerminal()).isFalse();
  }

  // ---- helpers ------------------------------------------------------------

  private static final class RecordingMetrics implements VoucherStatusMetricsRecorder {
    int accepted;
    int terminalConflicts;
    final List<String> rejected = new ArrayList<>();

    @Override
    public void recordAccepted() {
      accepted++;
    }

    @Override
    public void recordRejected(String reasonCode) {
      rejected.add(reasonCode);
    }

    @Override
    public void recordTerminalConflict() {
      terminalConflicts++;
    }
  }

  private static String pub(String priv) {
    try {
      return HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(priv)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String signed(
      String priv, String voucherId, String status, long version, long createdAt) {
    return signed(priv, voucherId, status, version, createdAt, ISSUER, null, null);
  }

  private static String signed(
      String priv,
      String voucherId,
      String status,
      long version,
      long createdAt,
      String issuerId,
      String issuerPubkeyTag,
      String unused) {
    return frame(signedNode(priv, voucherId, status, version, createdAt, issuerId, issuerPubkeyTag));
  }

  private static List<List<String>> tags(
      String voucherId, String status, long version, String issuerId, String issuerPubkeyTag) {
    List<List<String>> tags = new ArrayList<>();
    tags.add(List.of("d", "voucher:" + voucherId));
    tags.add(List.of("status", status));
    tags.add(List.of("state_version", Long.toString(version)));
    tags.add(List.of("issuer_id", issuerId));
    if (issuerPubkeyTag != null) {
      tags.add(List.of("issuer_pubkey", issuerPubkeyTag));
    }
    return tags;
  }

  private static ObjectNode signedNode(
      String priv,
      String voucherId,
      String status,
      long version,
      long createdAt,
      String issuerId,
      String issuerPubkeyTag) {
    try {
      String pubkey = pub(priv);
      List<List<String>> tags = tags(voucherId, status, version, issuerId, issuerPubkeyTag);
      String id = CanonicalJson.eventId(pubkey, createdAt, 30078, tags, "");
      byte[] sig = Schnorr.sign(HEX.parseHex(id), HEX.parseHex(priv), new byte[32]);
      ObjectNode ev = node(pubkey, createdAt, tags);
      ev.put("id", id);
      ev.put("sig", HEX.formatHex(sig));
      return ev;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String unsigned(String voucherId, String status, long version, long createdAt) {
    return frame(node(null, createdAt, tags(voucherId, status, version, ISSUER, null)));
  }

  private static ObjectNode node(String pubkey, long createdAt, List<List<String>> tags) {
    ObjectNode ev = new ObjectMapper().createObjectNode();
    if (pubkey != null) {
      ev.put("pubkey", pubkey);
    }
    ev.put("kind", 30078);
    ev.put("created_at", createdAt);
    ArrayNode arr = ev.putArray("tags");
    for (List<String> tag : tags) {
      ArrayNode t = arr.addArray();
      tag.forEach(t::add);
    }
    ev.put("content", "");
    return ev;
  }

  private static String frame(ObjectNode ev) {
    return "[\"EVENT\",\"sub\"," + ev + "]";
  }

  private static TransactionEvent send(String id, String voucherRef, Instant at) {
    return new TransactionEvent(
        Optional.of(id),
        "op-" + id,
        OperationKind.SEND,
        "https://mint.imani.casa",
        "sat",
        at,
        Instant.ofEpochSecond(at.getEpochSecond()),
        "pk",
        Optional.empty(),
        List.of(
            new ProofRef(
                64,
                "00ad12ef",
                "02" + id.hashCode(),
                Optional.of("s"),
                Optional.of("c"),
                Optional.empty(),
                Optional.empty())),
        List.of(),
        List.of(),
        Optional.empty(),
        Optional.of(voucherRef),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(0L),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        PrivacyMode.FULL,
        Optional.empty(),
        Optional.empty(),
        1,
        new NostrEventMetadata(
            Optional.of(id),
            9079,
            Optional.empty(),
            Optional.empty(),
            Instant.ofEpochSecond(at.getEpochSecond())));
  }
}
