package xyz.tcheeric.cashu.ledger.core.state;

import java.util.List;
import java.util.Map;
import xyz.tcheeric.cashu.ledger.core.model.VoucherNode;
import xyz.tcheeric.cashu.ledger.core.model.VoucherTree;

/** Verifies value conservation across split nodes in a voucher tree. */
public final class ValueConservationVerifier {

  private ValueConservationVerifier() {}

  public static boolean isConserved(VoucherNode node, VoucherTree tree) {
    if (node == null || tree == null) {
      return true;
    }
    List<String> children = tree.childrenMap().getOrDefault(node.voucherId(), List.of());
    if (children.isEmpty()) {
      return true;
    }
    Map<String, VoucherNode> nodes = tree.nodes();
    long childFace = 0L;
    long childTokens = 0L;
    for (String childId : children) {
      VoucherNode child = nodes.get(childId);
      if (child != null) {
        childFace += child.originalFaceValue();
        childTokens += child.originalTokenAmount();
      }
    }
    return childFace == node.originalFaceValue() && childTokens == node.originalTokenAmount();
  }
}
