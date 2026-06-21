package xyz.tcheeric.cashu.ledger.trace.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The four-tier schema-version compatibility ladder (design §5.12). For a ledger at
 * {@code currentSchemaVersion = N}, an incoming {@code schema_version} is:
 *
 * <ul>
 *   <li>{@code N} / {@code N-1} — {@link Tier#ACCEPTED} (validated silently);</li>
 *   <li>{@code N-2} — {@link Tier#DEPRECATED} (accepted, but {@code TRACE_DEPRECATED_SCHEMA});</li>
 *   <li>{@code ≤ N-3} — {@link Tier#UNSUPPORTED} (rejected, {@code TRACE_UNSUPPORTED_SCHEMA});</li>
 *   <li>{@code > N} — {@link Tier#FUTURE} (rejected, {@code TRACE_FUTURE_SCHEMA}).</li>
 * </ul>
 */
public final class SchemaCompatibility {

    /** The handling tier for an incoming schema version. */
    public enum Tier { ACCEPTED, DEPRECATED, UNSUPPORTED, FUTURE }

    private SchemaCompatibility() {
    }

    public static Tier classify(int version, int current) {
        if (version > current) {
            return Tier.FUTURE;
        }
        if (version >= current - 1) {
            return Tier.ACCEPTED;
        }
        if (version == current - 2) {
            return Tier.DEPRECATED;
        }
        return Tier.UNSUPPORTED;
    }

    /** Whether an already-indexed event's version is deprecated or older (stale on reads). */
    public static boolean isDeprecated(int version, int current) {
        return version <= current - 2;
    }

    /** The inclusive band the ledger accepts on ingest: {@code max(1, N-2) .. N}. */
    public static List<Integer> supportedVersions(int current) {
        List<Integer> versions = new ArrayList<>();
        for (int v = Math.max(1, current - 2); v <= current; v++) {
            versions.add(v);
        }
        return versions;
    }

    /** The accepted-but-deprecated subset: {@code [N-2]} when it is a valid version, else empty. */
    public static List<Integer> deprecatedVersions(int current) {
        int deprecated = current - 2;
        return deprecated >= 1 ? List.of(deprecated) : List.of();
    }
}
