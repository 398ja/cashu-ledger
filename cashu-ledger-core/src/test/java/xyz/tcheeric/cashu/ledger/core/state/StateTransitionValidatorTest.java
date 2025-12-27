package xyz.tcheeric.cashu.ledger.core.state;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.core.model.TransitionActor;
import xyz.tcheeric.cashu.ledger.core.model.VoucherStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StateTransitionValidatorTest {

    /**
     * Validates that a CLAIMED transition is accepted when the voucher was ISSUED and claim metadata is present.
     */
    @Test
    void shouldAcceptClaimWhenIssuedAndClaimDataPresent() {
        // Arrange
        VoucherStateSnapshot issuedSnapshot = new VoucherStateSnapshot(
                "v-123",
                VoucherStatus.ISSUED,
                VoucherStatus.UNKNOWN,
                0,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                "evt-0",
                "relay"
        );
        VoucherStateTransition claim = new VoucherStateTransition(
                "v-123",
                VoucherStatus.ISSUED,
                VoucherStatus.CLAIMED,
                1,
                Instant.parse("2025-01-01T00:10:00Z"),
                TransitionActor.RECIPIENT,
                null,
                Instant.parse("2025-02-01T00:00:00Z"),
                java.util.List.of(),
                "npub1recipient",
                Instant.parse("2025-01-01T00:10:00Z"),
                null,
                null,
                null,
                null,
                Instant.parse("2025-01-01T00:05:00Z"),
                "evt-1",
                "relay",
                Instant.parse("2025-01-01T00:00:00Z")
        );
        StateTransitionValidator validator = new StateTransitionValidator();

        // Act
        StateValidationResult result = validator.validate(claim, issuedSnapshot);

        // Assert
        assertThat(result.valid()).isTrue();
    }

    /**
     * Ensures redemption is rejected if an invalid transition actor is provided.
     */
    @Test
    void shouldRejectRedeemedWhenActorInvalid() {
        // Arrange
        VoucherStateSnapshot claimedSnapshot = new VoucherStateSnapshot(
                "v-456",
                VoucherStatus.CLAIMED,
                VoucherStatus.ISSUED,
                1,
                Instant.parse("2025-01-01T00:10:00Z"),
                Instant.parse("2025-01-01T00:05:00Z"),
                "evt-claim",
                "relay"
        );
        VoucherStateTransition redeem = new VoucherStateTransition(
                "v-456",
                VoucherStatus.CLAIMED,
                VoucherStatus.REDEEMED,
                2,
                Instant.parse("2025-01-01T00:20:00Z"),
                TransitionActor.SENDER,
                null,
                Instant.parse("2025-02-01T00:00:00Z"),
                java.util.List.of(),
                "npub1recipient",
                Instant.parse("2025-01-01T00:10:00Z"),
                "npub1recipient",
                Instant.parse("2025-01-01T00:20:00Z"),
                null,
                null,
                Instant.parse("2025-01-01T00:20:00Z"),
                "evt-redeem",
                "relay",
                Instant.parse("2025-01-01T00:00:00Z")
        );
        StateTransitionValidator validator = new StateTransitionValidator();

        // Act
        StateValidationResult result = validator.validate(redeem, claimedSnapshot);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("Transition actor must be recipient or issuer");
    }

    /**
     * Verifies that transitions attempting to change a terminal state are rejected.
     */
    @Test
    void shouldRejectChangingTerminalState() {
        // Arrange
        VoucherStateSnapshot redeemedSnapshot = new VoucherStateSnapshot(
                "v-789",
                VoucherStatus.REDEEMED,
                VoucherStatus.CLAIMED,
                3,
                Instant.parse("2025-01-01T00:30:00Z"),
                Instant.parse("2025-01-01T00:25:00Z"),
                "evt-redeemed",
                "relay"
        );
        VoucherStateTransition reclaim = new VoucherStateTransition(
                "v-789",
                VoucherStatus.REDEEMED,
                VoucherStatus.RECLAIMED,
                4,
                Instant.parse("2025-01-01T00:40:00Z"),
                TransitionActor.SENDER,
                "duplicate_send",
                Instant.parse("2025-02-01T00:00:00Z"),
                java.util.List.of(),
                null,
                null,
                null,
                null,
                "npub1sender",
                Instant.parse("2025-01-01T00:40:00Z"),
                Instant.parse("2025-01-01T00:40:00Z"),
                "evt-reclaim",
                "relay",
                Instant.parse("2025-01-01T00:00:00Z")
        );
        StateTransitionValidator validator = new StateTransitionValidator();

        // Act
        StateValidationResult result = validator.validate(reclaim, redeemedSnapshot);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("already in terminal status");
    }
}
