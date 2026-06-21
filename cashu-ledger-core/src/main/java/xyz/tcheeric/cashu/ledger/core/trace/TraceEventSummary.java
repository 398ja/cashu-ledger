package xyz.tcheeric.cashu.ledger.core.trace;

import java.time.Instant;

/**
 * A secret-free summary of a transaction event, safe to surface through interfaces that
 * are not gated by the trace access-control (e.g. the voucher inspect API). Carries only
 * non-sensitive identifiers and the derived activity classification — never proof secrets,
 * signatures, or Lightning payloads.
 *
 * @param eventId       the Nostr event id
 * @param operationId   the producer operation id
 * @param kind          the operation kind wire value
 * @param mintUrl       the mint the operation ran against
 * @param transitionAt  when the operation occurred
 * @param activity      the derived activity classification ({@code active}/{@code terminal})
 * @param activityReason why the event is terminal, when applicable
 */
public record TraceEventSummary(
        String eventId,
        String operationId,
        String kind,
        String mintUrl,
        Instant transitionAt,
        String activity,
        String activityReason) {
}
