package xyz.tcheeric.cashu.ledger.core.service;

import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;
import xyz.tcheeric.cashu.ledger.core.model.TraversalDirection;
import xyz.tcheeric.cashu.ledger.core.state.StatusChange;
import xyz.tcheeric.cashu.ledger.core.state.VoucherSearchCriteria;
import xyz.tcheeric.cashu.ledger.core.state.UnclaimedStatusResult;
import xyz.tcheeric.cashu.ledger.core.state.ReclaimOutcome;
import xyz.tcheeric.cashu.ledger.core.state.VerificationReport;
import xyz.tcheeric.cashu.ledger.core.state.HistoryResult;

import java.util.Optional;
import java.util.List;
import java.time.Instant;

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

    /**
     * Fetch status history for a voucher.
     */
    HistoryResult fetchHistory(String voucherId, Instant since, Instant until, int limit);

    /**
     * Search for vouchers using criteria.
     */
    List<VoucherNode> search(VoucherSearchCriteria criteria);

    /**
     * List unclaimed vouchers filtered by sender (issuer pubkey).
     */
    List<VoucherNode> listUnclaimed(String sentBy, int limit);

    /**
     * Check reclaim status for an unclaimed voucher.
     */
    UnclaimedStatusResult checkUnclaimedStatus(String voucherId, String tokenFilePath);

    /**
     * Attempt to reclaim an unclaimed voucher.
     */
    ReclaimOutcome reclaim(String voucherId, String tokenFilePath);

    /**
     * Verify voucher integrity (signature/value/hierarchy).
     */
    VerificationReport verify(String voucherId);

    @Override
    default void close() {
        // Optional for implementations
    }
}
