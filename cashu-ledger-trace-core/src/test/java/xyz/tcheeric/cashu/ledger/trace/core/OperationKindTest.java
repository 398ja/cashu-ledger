package xyz.tcheeric.cashu.ledger.trace.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OperationKind} category predicates and wire round-tripping.
 */
class OperationKindTest {

    /** Tests that only mint-state-change kinds report as such. */
    @Test
    void shouldClassifyMintStateChangeKinds() {
        // Then
        assertThat(OperationKind.MINT.isMintStateChange()).isTrue();
        assertThat(OperationKind.SWAP.isMintStateChange()).isTrue();
        assertThat(OperationKind.MELT.isMintStateChange()).isTrue();
        assertThat(OperationKind.MELT_REFUND.isMintStateChange()).isTrue();
        assertThat(OperationKind.RESTORE.isMintStateChange()).isTrue();
        assertThat(OperationKind.SEND.isMintStateChange()).isFalse();
        assertThat(OperationKind.MINT_QUOTE_REQUESTED.isMintStateChange()).isFalse();
    }

    /** Tests that edge producers and consumers are identified per FR-5. */
    @Test
    void shouldClassifyEdgeProducersAndConsumers() {
        // Then: producers begin edges; consumers terminate them
        assertThat(OperationKind.MINT.isEdgeProducer()).isTrue();
        assertThat(OperationKind.RESTORE.isEdgeProducer()).isTrue();
        assertThat(OperationKind.MELT_REFUND.isEdgeProducer()).isTrue();
        assertThat(OperationKind.SWAP.isEdgeProducer()).isTrue();
        assertThat(OperationKind.SWAP.isEdgeConsumer()).isTrue();
        assertThat(OperationKind.MELT.isEdgeConsumer()).isTrue();
        assertThat(OperationKind.MINT.isEdgeConsumer()).isFalse();
        assertThat(OperationKind.RESTORE.isEdgeConsumer()).isFalse();
    }

    /** Tests that SEND and RECEIVE are the only possession kinds. */
    @Test
    void shouldClassifyPossessionKinds() {
        // Then
        assertThat(OperationKind.SEND.isPossession()).isTrue();
        assertThat(OperationKind.RECEIVE.isPossession()).isTrue();
        assertThat(OperationKind.SWAP.isPossession()).isFalse();
    }

    /** Tests that terminal kinds are identified for activity classification. */
    @Test
    void shouldClassifyTerminalKinds() {
        // Then
        assertThat(OperationKind.MELT.isTerminalKind()).isTrue();
        assertThat(OperationKind.MELT_FAILED.isTerminalKind()).isTrue();
        assertThat(OperationKind.MINT_FAILED.isTerminalKind()).isTrue();
        assertThat(OperationKind.EVENT_PRUNED.isTerminalKind()).isTrue();
        assertThat(OperationKind.SWAP.isTerminalKind()).isFalse();
    }

    /** Tests that the wire value is the lowercased enum name and round-trips. */
    @Test
    void shouldRoundTripWireValue() {
        // Act / Then
        assertThat(OperationKind.MELT_REFUND.wireValue()).isEqualTo("melt_refund");
        assertThat(OperationKind.fromWire("swap")).isEqualTo(OperationKind.SWAP);
        assertThat(OperationKind.fromWire("MINT")).isEqualTo(OperationKind.MINT);
    }

    /** Tests that an unrecognised wire value is rejected. */
    @Test
    void shouldRejectUnknownWireValue() {
        // Act / Then
        assertThatThrownBy(() -> OperationKind.fromWire("stake"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
