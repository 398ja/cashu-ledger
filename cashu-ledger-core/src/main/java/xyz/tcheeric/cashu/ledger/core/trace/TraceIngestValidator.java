package xyz.tcheeric.cashu.ledger.core.trace;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import nostr.crypto.schnorr.Schnorr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.ledger.trace.core.CanonicalJson;
import xyz.tcheeric.cashu.ledger.trace.core.OperationInvariants;
import xyz.tcheeric.cashu.ledger.trace.core.SchemaCompatibility;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * Validates a parsed kind-9079 event at ingest before it is stored (design §4.5 /
 * §5.9 / §7.1). Checks, in order: canonical id integrity, Schnorr signature,
 * producer attestation, schema-version band, timestamp consistency, clock skew, and
 * per-operation invariants. Returns the first rejection, or empty if acceptable.
 */
public final class TraceIngestValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceIngestValidator.class);
    private static final HexFormat HEX = HexFormat.of();

    private final ProducerAttestationConfig producers;
    private final int currentSchemaVersion;
    private final long futureSkewSeconds;
    private final long staleWindowSeconds;
    private final LongSupplier nowMillis;

    public TraceIngestValidator(ProducerAttestationConfig producers, int currentSchemaVersion,
                                long futureSkewSeconds, long staleWindowSeconds, LongSupplier nowMillis) {
        this.producers = producers;
        this.currentSchemaVersion = currentSchemaVersion;
        this.futureSkewSeconds = futureSkewSeconds;
        this.staleWindowSeconds = staleWindowSeconds;
        this.nowMillis = nowMillis;
    }

    /** Default skews: 60s future, 24h stale (design FR-20). */
    public static TraceIngestValidator withDefaults(ProducerAttestationConfig producers) {
        return new TraceIngestValidator(producers, 1, 60, 24 * 60 * 60, System::currentTimeMillis);
    }

    /**
     * Validates {@code parsed}. {@code allowHistorical} bypasses the stale-timestamp
     * rejection for admin backfills.
     */
    public Optional<IngestRejection> validate(ParsedTraceEvent parsed, boolean allowHistorical) {
        TransactionEvent event = parsed.event();
        String claimedId = event.eventId().orElse(null);
        if (claimedId == null) {
            return reject("MISSING_ID", "event has no id");
        }

        String canonicalId = CanonicalJson.eventId(event);
        if (!canonicalId.equals(claimedId)) {
            return reject("INVALID_ID",
                    "event id does not match canonical serialisation (possible tampering)");
        }

        Optional<IngestRejection> signature = verifySignature(claimedId, event.producerPubkey(),
                parsed.signatureHex());
        if (signature.isPresent()) {
            return signature;
        }

        if (!producers.isAuthorised(event.mintUrl(), event.producerPubkey())) {
            return reject("TRACE_FORBIDDEN_PRODUCER",
                    "signer " + event.producerPubkey() + " is not an authorised producer for "
                            + event.mintUrl());
        }

        Optional<IngestRejection> schema = checkSchemaVersion(event.schemaVersion());
        if (schema.isPresent()) {
            return schema;
        }

        long expectedCreatedAt = Math.floorDiv(event.transitionAt().toEpochMilli(), 1000L);
        if (event.createdAt().getEpochSecond() != expectedCreatedAt) {
            return reject("TIMESTAMP_MISMATCH",
                    "created_at must equal floor(transition_at/1000)");
        }

        Optional<IngestRejection> skew = checkClockSkew(event.createdAt().getEpochSecond(), allowHistorical);
        if (skew.isPresent()) {
            return skew;
        }

        List<OperationInvariants.Violation> violations = OperationInvariants.validate(event);
        if (!violations.isEmpty()) {
            OperationInvariants.Violation first = violations.get(0);
            return reject("INVALID_OPERATION", first.code() + ": " + first.message());
        }
        return Optional.empty();
    }

    private Optional<IngestRejection> verifySignature(String eventId, String pubkeyHex, String sigHex) {
        try {
            boolean valid = Schnorr.verify(HEX.parseHex(eventId), HEX.parseHex(pubkeyHex), HEX.parseHex(sigHex));
            return valid ? Optional.empty()
                    : reject("INVALID_SIGNATURE", "Schnorr signature verification failed");
        } catch (Exception e) {
            return reject("INVALID_SIGNATURE", "signature could not be verified: " + e.getMessage());
        }
    }

    private Optional<IngestRejection> checkSchemaVersion(int version) {
        return switch (SchemaCompatibility.classify(version, currentSchemaVersion)) {
            case FUTURE -> reject("TRACE_FUTURE_SCHEMA",
                    "schema_version " + version + " is newer than the ledger supports ("
                            + currentSchemaVersion + ")");
            case UNSUPPORTED -> reject("TRACE_UNSUPPORTED_SCHEMA",
                    "schema_version " + version + " is no longer accepted");
            case DEPRECATED -> {
                LOGGER.warn("TRACE_DEPRECATED_SCHEMA schema_version={} current={}",
                        version, currentSchemaVersion);
                yield Optional.empty();
            }
            case ACCEPTED -> Optional.empty();
        };
    }

    private Optional<IngestRejection> checkClockSkew(long createdAtSeconds, boolean allowHistorical) {
        long nowSeconds = nowMillis.getAsLong() / 1000L;
        if (createdAtSeconds > nowSeconds + futureSkewSeconds) {
            return reject("CLOCK_SKEW_FUTURE", "created_at is too far in the future");
        }
        if (!allowHistorical && createdAtSeconds < nowSeconds - staleWindowSeconds) {
            return reject("CLOCK_SKEW_STALE",
                    "created_at is older than the retention window; use --allow-historical to ingest");
        }
        return Optional.empty();
    }

    private static Optional<IngestRejection> reject(String code, String message) {
        return Optional.of(new IngestRejection(code, message));
    }
}
