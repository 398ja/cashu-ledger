package xyz.tcheeric.cashu.ledger.core.service;

import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;
import xyz.tcheeric.cashu.ledger.core.model.TraversalDirection;

import java.util.Optional;

public interface VoucherLedgerService extends AutoCloseable {

    /**
     * Fetch a voucher by ID from configured relays.
     *
     * @param voucherId voucher identifier
     * @return populated voucher node if found
     */
    Optional<VoucherNode> fetchVoucher(String voucherId);

    /**
     * Build a voucher tree by traversing parents/children from the target.
     *
     * @param voucherId target voucher ID
     * @param maxDepth  traversal depth
     * @param direction traversal direction
     * @return built tree if the voucher was found
     */
    Optional<VoucherTree> buildTree(String voucherId, int maxDepth, TraversalDirection direction);

    @Override
    default void close() {
        // Optional for implementations
    }
}
