package xyz.tcheeric.cashu.ledger.core.state;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.core.model.BackingStrategy;
import xyz.tcheeric.cashu.ledger.core.model.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.core.model.TransitionActor;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStateMetadata;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherStateJournalTest {

    /**
     * Confirms that a newer state version replaces the existing snapshot.
     */
    @Test
    void shouldAcceptNewerStateVersion() {
        // Arrange
        VoucherStateJournal journal = new VoucherStateJournal();
        VoucherNode issued = node(
                "v-100",
                VoucherStatus.ISSUED,
                VoucherStatus.UNKNOWN,
                0,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-02T00:00:00Z"),
                "evt-0"
        );
        VoucherNode claimed = node(
                "v-100",
                VoucherStatus.CLAIMED,
                VoucherStatus.ISSUED,
                1,
                Instant.parse("2025-01-01T00:10:00Z"),
                Instant.parse("2025-01-02T00:10:00Z"),
                "evt-1"
        );

        // Act
        VoucherStateJournal.StateDecision firstDecision = journal.record(issued);
        VoucherStateJournal.StateDecision secondDecision = journal.record(claimed);

        // Assert
        assertThat(firstDecision.decision()).isEqualTo(VoucherStateJournal.StateDecisionType.ACCEPTED);
        assertThat(secondDecision.decision()).isEqualTo(VoucherStateJournal.StateDecisionType.ACCEPTED);
        assertThat(journal.snapshot("v-100")).isPresent();
        assertThat(journal.snapshot("v-100").orElseThrow().status()).isEqualTo(VoucherStatus.CLAIMED);
        assertThat(journal.snapshot("v-100").orElseThrow().stateVersion()).isEqualTo(1);
    }

    /**
     * Ensures stale state versions are rejected to preserve ordering.
     */
    @Test
    void shouldRejectStaleStateVersion() {
        // Arrange
        VoucherStateJournal journal = new VoucherStateJournal();
        VoucherNode issued = node(
                "v-200",
                VoucherStatus.ISSUED,
                VoucherStatus.UNKNOWN,
                0,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-02T00:00:00Z"),
                "evt-issue"
        );
        VoucherNode claimed = node(
                "v-200",
                VoucherStatus.CLAIMED,
                VoucherStatus.ISSUED,
                1,
                Instant.parse("2025-01-01T00:10:00Z"),
                Instant.parse("2025-01-02T00:10:00Z"),
                "evt-claim"
        );
        VoucherNode stale = node(
                "v-200",
                VoucherStatus.REDEEMED,
                VoucherStatus.CLAIMED,
                1,
                Instant.parse("2025-01-01T00:20:00Z"),
                Instant.parse("2025-01-02T00:20:00Z"),
                "evt-redeem"
        );

        // Act
        journal.record(issued);
        journal.record(claimed);
        VoucherStateJournal.StateDecision staleDecision = journal.record(stale);

        // Assert
        assertThat(staleDecision.decision()).isEqualTo(VoucherStateJournal.StateDecisionType.REJECTED_INVALID);
        assertThat(staleDecision.message()).contains("State version 1 does not match expected 2");
        assertThat(journal.snapshot("v-200")).isPresent();
        assertThat(journal.snapshot("v-200").orElseThrow().status()).isEqualTo(VoucherStatus.CLAIMED);
    }

    private VoucherNode node(
            String voucherId,
            VoucherStatus status,
            VoucherStatus previousStatus,
            long stateVersion,
            Instant transitionAt,
            Instant createdAt,
            String eventId
    ) {
        VoucherStateMetadata metadata = new VoucherStateMetadata(
                previousStatus,
                stateVersion,
                transitionAt,
                status == VoucherStatus.RECLAIMED ? TransitionActor.SENDER : TransitionActor.RECIPIENT,
                null,
                status == VoucherStatus.CLAIMED ? "npub1recipient" : null,
                status == VoucherStatus.CLAIMED ? transitionAt : null,
                null,
                null,
                status == VoucherStatus.RECLAIMED ? "npub1sender" : null,
                status == VoucherStatus.RECLAIMED ? transitionAt : null,
                List.of()
        );
        NostrEventMetadata eventMetadata = new NostrEventMetadata(
                eventId,
                "a".repeat(64),
                createdAt,
                "relay",
                30078,
                List.of(),
                null
        );
        return new VoucherNode(
                voucherId,
                "merchant-1",
                "a".repeat(64),
                1000,
                2,
                1000,
                1000,
                1000,
                "EUR",
                BackingStrategy.PROPORTIONAL,
                BigDecimal.ONE,
                status,
                metadata,
                transitionAt,
                transitionAt.plusSeconds(3600),
                "memo",
                Map.of(),
                List.of(),
                eventMetadata
        );
    }
}
