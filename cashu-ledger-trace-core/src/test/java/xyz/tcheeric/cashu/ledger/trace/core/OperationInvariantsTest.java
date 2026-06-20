package xyz.tcheeric.cashu.ledger.trace.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OperationInvariants}: per-kind cardinality and balance,
 * required references, and hard size/cardinality limits.
 */
class OperationInvariantsTest {

    /** Tests that a well-formed SWAP passes all invariants. */
    @Test
    void shouldAcceptValidSwap() {
        // Arrange
        TransactionEvent swap = TraceFixtures.validSwap();

        // Act / Then
        assertThat(OperationInvariants.isValid(swap)).isTrue();
    }

    /** Tests that a well-formed MINT (no inputs, outputs == quote amount) passes. */
    @Test
    void shouldAcceptValidMint() {
        // Arrange
        TransactionEvent mint = TraceFixtures.validMint();

        // Act / Then
        assertThat(OperationInvariants.isValid(mint)).isTrue();
    }

    /** Tests that a SWAP whose inputs and outputs do not balance is rejected (B1). */
    @Test
    void shouldRejectUnbalancedSwap() {
        // Arrange: 128 in, 100 out, fee 0
        TransactionEvent bad = TraceFixtures.event()
                .kind(OperationKind.SWAP)
                .inputs(List.of(TraceFixtures.fullProof(64, "a1"), TraceFixtures.fullProof(64, "b2")))
                .outputs(List.of(TraceFixtures.fullProof(100, "c3")))
                .feeAmount(0L)
                .build();

        // Act
        List<OperationInvariants.Violation> violations = OperationInvariants.validate(bad);

        // Then
        assertThat(violations).extracting(OperationInvariants.Violation::code).contains("B1");
    }

    /** Tests that a MINT carrying inputs is rejected (E1). */
    @Test
    void shouldRejectMintWithInputs() {
        // Arrange
        TransactionEvent bad = TraceFixtures.event()
                .kind(OperationKind.MINT)
                .inputs(List.of(TraceFixtures.fullProof(64, "a1")))
                .outputs(List.of(TraceFixtures.fullProof(64, "b2")))
                .lightning(new LightningRef("q", TraceFixtures.MINT_URL, Optional.empty(), Optional.empty(),
                        Optional.of(64L), Optional.empty(), OperationKind.MINT_QUOTE_REQUESTED, false))
                .build();

        // Act
        List<OperationInvariants.Violation> violations = OperationInvariants.validate(bad);

        // Then
        assertThat(violations).extracting(OperationInvariants.Violation::code).contains("E1");
    }

    /** Tests that a SEND without a bundle_id is rejected (L1). */
    @Test
    void shouldRejectSendWithoutBundleId() {
        // Arrange
        TransactionEvent send = TraceFixtures.event()
                .kind(OperationKind.SEND)
                .inputs(List.of(TraceFixtures.fullProof(64, "c3")))
                .build();

        // Act
        List<OperationInvariants.Violation> violations = OperationInvariants.validate(send);

        // Then
        assertThat(violations).extracting(OperationInvariants.Violation::code).contains("L1");
    }

    /** Tests that a MELT_FAILED without an error_code is rejected (X1). */
    @Test
    void shouldRejectFailedMeltWithoutErrorCode() {
        // Arrange
        TransactionEvent failed = TraceFixtures.event()
                .kind(OperationKind.MELT_FAILED)
                .inputs(List.of(TraceFixtures.fullProof(64, "d4")))
                .lightning(new LightningRef("q", TraceFixtures.MINT_URL, Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), OperationKind.MELT_QUOTE_REQUESTED, false))
                .build();

        // Act
        List<OperationInvariants.Violation> violations = OperationInvariants.validate(failed);

        // Then
        assertThat(violations).extracting(OperationInvariants.Violation::code).contains("X1");
    }

    /** Tests that exceeding the maximum input count is rejected (TOO_MANY_INPUTS). */
    @Test
    void shouldRejectTooManyInputs() {
        // Arrange: 65 inputs (limit is 64), balanced to isolate the cardinality failure
        java.util.List<ProofRef> inputs = new java.util.ArrayList<>();
        for (int i = 0; i < OperationInvariants.MAX_INPUTS + 1; i++) {
            inputs.add(TraceFixtures.fullProof(1, String.format("%02x", i % 256)));
        }
        TransactionEvent bad = TraceFixtures.event()
                .kind(OperationKind.SWAP)
                .inputs(inputs)
                .outputs(List.of(TraceFixtures.fullProof(inputs.size(), "ff")))
                .feeAmount(0L)
                .build();

        // Act
        List<OperationInvariants.Violation> violations = OperationInvariants.validate(bad);

        // Then
        assertThat(violations).extracting(OperationInvariants.Violation::code).contains("TOO_MANY_INPUTS");
    }

    /** Tests that EVENT_PRUNED is rejected when submitted by a producer (P1). */
    @Test
    void shouldRejectProducerSubmittedPrunedKind() {
        // Arrange
        TransactionEvent pruned = TraceFixtures.event().kind(OperationKind.EVENT_PRUNED).build();

        // Act
        List<OperationInvariants.Violation> violations = OperationInvariants.validate(pruned);

        // Then
        assertThat(violations).extracting(OperationInvariants.Violation::code).contains("P1");
    }

    /** Tests that a MELT with no fee amount is rejected (F1). */
    @Test
    void shouldRejectMeltWithoutFee() {
        // Arrange: MELT in 64, quote 60, no fee, change 4 -> structurally missing fee
        TransactionEvent melt = TraceFixtures.event()
                .kind(OperationKind.MELT)
                .inputs(List.of(TraceFixtures.fullProof(64, "d4")))
                .outputs(List.of(TraceFixtures.fullProof(4, "07")))
                .outputRoles(List.of(OutputRole.CHANGE))
                .lightning(new LightningRef("q", TraceFixtures.MINT_URL, Optional.empty(), Optional.empty(),
                        Optional.of(60L), Optional.empty(), OperationKind.MELT_QUOTE_REQUESTED, false))
                .build();

        // Act
        List<OperationInvariants.Violation> violations = OperationInvariants.validate(melt);

        // Then
        assertThat(violations).extracting(OperationInvariants.Violation::code).contains("F1");
    }
}
