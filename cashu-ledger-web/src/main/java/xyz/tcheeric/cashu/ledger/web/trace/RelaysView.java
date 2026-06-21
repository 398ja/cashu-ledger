package xyz.tcheeric.cashu.ledger.web.trace;

import java.util.List;

/**
 * The trace ledger's relay set and schema-version discovery document (design §5.9 / §5.5).
 * Producer SDKs read this to learn the relays the ledger consumes and the schema-version
 * band it accepts, refreshing on any schema-deprecation response.
 *
 * @param relays                    the relays the ledger subscribes to
 * @param currentSchemaVersion      the schema version the ledger emits
 * @param supportedSchemaVersions   the inclusive band of versions accepted on ingest
 * @param deprecatedSchemaVersions  the subset that triggers TRACE_DEPRECATED_SCHEMA
 */
public record RelaysView(
        List<String> relays,
        int currentSchemaVersion,
        List<Integer> supportedSchemaVersions,
        List<Integer> deprecatedSchemaVersions) {
}
