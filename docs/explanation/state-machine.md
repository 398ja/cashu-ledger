# Voucher State Machine Design

This document explains the voucher lifecycle and state transitions in cashu-ledger.

## Overview

Cashu vouchers progress through a defined lifecycle represented by states. Understanding these states is crucial for debugging, auditing, and investigating voucher behavior.

## Voucher States

| State | Description | Terminal |
|-------|-------------|----------|
| `ISSUED` | Voucher created and published, not yet imported by recipient | No |
| `CLAIMED` | Voucher imported into a wallet (ownership established) | No |
| `SPLIT` | Voucher subdivided into child vouchers | Yes |
| `REDEEMED` | Voucher exchanged for Lightning payment or settlement | Yes |
| `RECLAIMED` | Unclaimed voucher recovered by original sender | Yes |
| `REVOKED` | Voucher invalidated by issuer | Yes |
| `EXPIRED` | Voucher past expiration date | Yes |

## State Machine Diagram

```
                                    ┌───────────┐
                                    │  REVOKED  │
                                    └───────────┘
                                          ▲
                                          │ (issuer revokes)
                                          │
┌──────────┐      ┌──────────┐      ┌─────┴─────┐      ┌──────────┐
│  ISSUED  │─────▶│ CLAIMED  │─────▶│ REDEEMED  │      │ EXPIRED  │
└────┬─────┘      └────┬─────┘      └───────────┘      └──────────┘
     │                 │                                     ▲
     │                 │ (split)                             │
     │                 ▼                              (auto-expire)
     │           ┌──────────┐                               │
     │           │  SPLIT   │───────────────────────────────┘
     │           └──────────┘
     │                 │
     │                 │ creates
     │                 ▼
     │           ┌─────────────────────────────┐
     │           │ Child Vouchers:             │
     │           │  - Send portion → ISSUED    │
     │           │  - Keep portion → CLAIMED   │
     │           └─────────────────────────────┘
     │
     │ (sender reclaims unclaimed)
     ▼
┌──────────┐
│RECLAIMED │
└──────────┘
```

## State Transitions

| From | To | Trigger | Actor |
|------|----|---------|-------|
| `ISSUED` | `CLAIMED` | Recipient imports token into wallet | Recipient |
| `ISSUED` | `RECLAIMED` | Sender recovers unclaimed voucher | Sender |
| `ISSUED` | `EXPIRED` | Expiration timestamp reached | System |
| `CLAIMED` | `REDEEMED` | Voucher exchanged for Lightning/settlement | Owner |
| `CLAIMED` | `SPLIT` | Voucher subdivided for partial send | Owner |
| `CLAIMED` | `EXPIRED` | Expiration timestamp reached | System |
| `*` | `REVOKED` | Issuer invalidates voucher | Issuer |

## State Semantics

### ISSUED State

A voucher enters `ISSUED` state when created by an issuer. At this point:

- Voucher is published to the Nostr ledger
- Token proofs are valid but not yet claimed
- Recipient has not imported the voucher
- Sender can reclaim if recipient never claims

### CLAIMED State

A voucher transitions to `CLAIMED` when a recipient imports it:

1. **Proof Swap** - Token proofs are swapped at the mint for fresh proofs
2. **Ownership Transfer** - Wallet now holds valid proofs
3. **Ledger Update** - Status published to Nostr relay

The claiming process is atomic with the proof swap. If the swap fails (proofs already spent), the claim fails.

### SPLIT State

When a `CLAIMED` voucher is split:

1. **Parent voucher** transitions to `SPLIT` (terminal)
2. **Send portion** creates new child in `ISSUED` state
3. **Keep portion** creates new child in `CLAIMED` state

```
V (CLAIMED, €30, 3000 sats)
  ├── split operation by User A
  │
  ├── V1 (ISSUED, €10, 1000 sats)   → sent to User B
  │       └── B imports → CLAIMED
  │
  └── V' (CLAIMED, €20, 2000 sats)  → stays with User A
```

**Value Conservation Rule:**
```
parent.faceValue == sum(children.faceValue)
parent.tokenAmount == sum(children.tokenAmount)
```

### RECLAIMED State

An `ISSUED` voucher can be reclaimed by the sender if:

1. Voucher state is `ISSUED` (not yet claimed)
2. Token proofs are still valid at mint
3. Sender has the original token data

Recovery process:
1. Sender checks proof status at mint
2. If proofs valid: swap to sender's wallet
3. Original voucher → `RECLAIMED`
4. New voucher created as `CLAIMED`

### Terminal States

Terminal states (`SPLIT`, `REDEEMED`, `RECLAIMED`, `REVOKED`, `EXPIRED`) cannot transition to other states. This ensures vouchers have a definite end state for auditing.

## Transition Guards

Each transition has guard conditions that must be satisfied:

### ISSUED → CLAIMED

- `previous_status` must be `issued`
- `claimed_by` pubkey must be present
- `claimed_at` >= `issued_at`
- Mint proof swap must succeed
- `state_version` increments by 1

### CLAIMED → SPLIT

- `split_into` list must be non-empty
- Child vouchers must exist or be created
- Value conservation must hold
- Parent marked terminal

### ISSUED → RECLAIMED

- `reclaimed_by` must match sender/issuer
- Mint must report proofs unspent
- `transition_actor` = sender

## Event Tags

State changes are recorded in Nostr events with specific tags:

| Tag | Description | States |
|-----|-------------|--------|
| `status` | Current state | All |
| `previous_status` | Prior state | All transitions |
| `state_version` | Monotonic counter | All |
| `transition_at` | Timestamp of change | All |
| `transition_actor` | Who performed it | All |
| `claimed_at` | Claim timestamp | CLAIMED |
| `claimed_by` | Recipient pubkey | CLAIMED |
| `split_at` | Split timestamp | SPLIT |
| `split_into` | Child voucher IDs | SPLIT |
| `redeemed_at` | Redemption timestamp | REDEEMED |
| `reclaimed_at` | Reclaim timestamp | RECLAIMED |

## State Conflict Resolution

When multiple events exist for the same voucher:

1. **Prefer highest `state_version`**
2. **Tie-breaker**: Larger `transition_at`
3. **Second tie-breaker**: Newer `created_at`
4. **Final tie-breaker**: Lexicographic event ID

Events with lower version than the current state are rejected.

## Implementation

### VoucherStatus Enum

```java
public enum VoucherStatus {
    ISSUED(0, false),
    CLAIMED(1, false),
    SPLIT(2, true),
    REDEEMED(3, true),
    RECLAIMED(3, true),
    REVOKED(3, true),
    EXPIRED(3, true);

    private final int order;
    private final boolean terminal;
}
```

### StateTransitionValidator

```java
public class StateTransitionValidator {

    public ValidationResult validate(VoucherStateTransition transition,
                                     VoucherStateSnapshot current) {
        // Check current state allows transition
        if (current.status().isTerminal()) {
            return ValidationResult.rejected("terminal_state_cannot_transition");
        }

        // Check transition is valid for current state
        if (!isValidTransition(current.status(), transition.to())) {
            return ValidationResult.rejected("invalid_state_transition");
        }

        // Apply specific guards
        return applyGuards(transition, current);
    }
}
```

## Use Cases

### Debugging a Failed Claim

```bash
# Check current status
cashu-ledger inspect v-1766748473969

# View history to see what happened
cashu-ledger history v-1766748473969

# Verify state consistency
cashu-ledger verify v-1766748473969
```

### Auditing Split Operations

```bash
# View the tree to see split relationships
cashu-ledger tree v-1766748473969

# Verify value conservation
cashu-ledger verify --check-hierarchy v-1766748473969
```

### Recovering Unclaimed Vouchers

```bash
# Check if voucher is still reclaimable
cashu-ledger inspect v-1766748473969
# If status is ISSUED, it may be reclaimable

# The actual reclaim happens in the wallet app
```

## Related Documentation

- [Voucher Specification](../reference/voucher-specification.md) - Complete event format
- [Architecture Overview](architecture.md) - System components
- [CLI Commands Reference](../reference/cli-commands.md) - Inspection commands
