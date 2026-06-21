package xyz.tcheeric.cashu.ledger.core.trace;

import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;

/**
 * A signed kind-9079 event parsed into its domain form plus the raw artefacts
 * needed for verification (the Schnorr signature and the original JSON).
 *
 * @param event        the parsed transaction event (with eventId present)
 * @param signatureHex the Schnorr signature from the raw event
 * @param rawJson      the original signed event JSON
 */
public record ParsedTraceEvent(TransactionEvent event, String signatureHex, String rawJson) {
}
