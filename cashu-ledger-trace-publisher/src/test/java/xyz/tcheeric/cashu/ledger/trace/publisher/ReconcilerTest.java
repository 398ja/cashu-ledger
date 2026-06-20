package xyz.tcheeric.cashu.ledger.trace.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.InMemoryOutboxStore;
import xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxRecord;

/**
 * Unit tests for {@link Reconciler}: classifying domain operations into delivered, in-flight,
 * and unaccounted, and surfacing stuck deliveries.
 */
class ReconcilerTest {

    private InMemoryOutboxStore outbox;

    @BeforeEach
    void setUp() {
        outbox = new InMemoryOutboxStore();
    }

    /** When every operation is either ledger-visible or pending, nothing is unaccounted. */
    @Test
    void shouldReportNoAlertWhenAllOperationsAccountedFor() {
        // Given: 10 domain ops, 8 ingested, 2 pending in the outbox
        enqueue("op-1", 0);
        enqueue("op-2", 0);
        Reconciler reconciler = new Reconciler(outbox, () -> 8L);

        // When: reconciling
        ReconciliationReport report = reconciler.reconcile(10);

        // Then: 2 missing, both explained by the outbox -> no alert
        assertThat(report.missing()).isEqualTo(2);
        assertThat(report.outboxPending()).isEqualTo(2);
        assertThat(report.unaccounted()).isZero();
        assertThat(report.alert()).isFalse();
    }

    /** Operations missing from the ledger with no pending row are unaccounted and alert. */
    @Test
    void shouldAlertOnUnaccountedOperations() {
        // Given: 10 domain ops, 7 ingested, only 1 pending -> 2 lost
        enqueue("op-1", 0);
        Reconciler reconciler = new Reconciler(outbox, () -> 7L);

        // When: reconciling
        ReconciliationReport report = reconciler.reconcile(10);

        // Then: 3 missing, 1 pending, 2 unaccounted -> alert
        assertThat(report.missing()).isEqualTo(3);
        assertThat(report.unaccounted()).isEqualTo(2);
        assertThat(report.alert()).isTrue();
    }

    /** A pending row past the retry threshold appears in the stuck view and alerts. */
    @Test
    void shouldSurfaceStuckDeliveries() {
        // Given: a row that has failed many times, ledger fully caught up otherwise
        enqueue("op-stuck", 9);
        Reconciler reconciler = new Reconciler(outbox, () -> 0L, 5);

        // When: reconciling with the domain count matching the single pending op
        ReconciliationReport report = reconciler.reconcile(1);

        // Then: the stuck row is surfaced and an alert is raised
        assertThat(report.stuckEvents()).extracting(OutboxRecord::operationId).containsExactly("op-stuck");
        assertThat(report.alert()).isTrue();
    }

    private void enqueue(String operationId, int attempts) {
        OutboxRecord row = new OutboxRecord(operationId, "evt-" + operationId, "{\"id\":\"x\"}",
                1000L, attempts, 1000L, xyz.tcheeric.cashu.ledger.trace.publisher.outbox.OutboxStatus.PENDING);
        outbox.enqueue(row);
    }
}
