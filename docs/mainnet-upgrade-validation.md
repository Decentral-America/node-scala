# Mainnet Upgrade Validation — Stagenet Protocol

## Context

DCC mainnet has ~3 million blocks. The genesis block and its pre-activated features
are immutable — any fix to the upgrade protocol must work **on top of an existing
chain**, not require a new genesis.

This document covers:
1. The stagenet test harness for validating mainnet upgrades
2. The root-cause protocol bug in `CommitToGenerationTransaction` state hashing
3. The required code fix before any mainnet upgrade

---

## 1. Stagenet Test Harness

### Goal

Reproduce the exact mainnet upgrade scenario in a controlled environment
before touching mainnet.

### Setup

```
stagenet
├── Legacy nodes (DecentralChain-PolyRepo)   ← simulate current mainnet
│   ├── legacy-main   (VPS — same as mainnet main node)
│   ├── legacy-gen-0  (LKE)
│   └── legacy-gen-1  (LKE)
│
└── Modern nodes (node-scala, this repo)     ← the upgrade target
    ├── modern-main   (VPS)
    ├── modern-gen-0  (LKE)
    └── modern-gen-1  (LKE)
```

**Legacy codebase:** `/Users/jourlez/Documents/Code/Blockchain/Legacy/DecentralChain-PolyRepo`

### Procedure

**Phase 1 — Run legacy chain to ~10,000 blocks**

1. Deploy legacy nodes to stagenet using the existing mainnet genesis config
   (same address-scheme, same feature set, same initial balances)
2. Let the legacy chain accumulate ~10,000 blocks — this simulates having
   "mainnet history" with all its CommitToGenerationTransaction entries,
   T0 finality endorsements, and block structure
3. Record the block hash and state hash at the handoff height (e.g. 10,000)

**Phase 2 — Upgrade modern nodes onto the same chain**

1. Start modern nodes (`node-scala`) pointing at the SAME stagenet genesis
2. Connect modern nodes to legacy nodes via P2P
3. Modern nodes must sync and accept the 10,000 legacy blocks without errors
4. Once synced, legacy nodes are shut down
5. Modern nodes take over block production

**Phase 3 — Validation criteria**

- [ ] Modern nodes sync all 10,000 legacy blocks (no validation failures)
- [ ] State hash at block 10,000 matches between legacy and modern nodes
- [ ] CommitToGeneration TXs from the legacy chain are honored in modern nodes
- [ ] Chain continues past block 10,000 with modern nodes as generators
- [ ] T0 DeterministicFinality activates and finalizes blocks
- [ ] T2 HotStuff BFT activates once BLS votes flow
- [ ] No chain splits during or after handoff

### Why 10,000 blocks?

The legacy chain will have multiple generation period boundaries (at blocks
100, 200, ..., 10,000 with `generation-period-length = 100`). This ensures
CommitToGenerationTransaction history is present and the modern node must
handle the full range of historical committed generator state.

---

## 2. Protocol Bug — CommitToGeneration State Hash Divergence

### Root Cause

`CommitToGenerationTransaction` (type 19) writes to `Keys.committedGenerators`
keyed by `(periodStart, commitmentHeight)`. When two nodes receive the same
TX but include it at different block heights (due to competing chains during
network partition), the RocksDB keys differ:

```
Chain A:  committedGenerators(period=101, height=47) → [gen-0, gen-1]
Chain B:  committedGenerators(period=101, height=48) → [gen-0, gen-1]
```

The state hash (feature 21) is a **cumulative running hash** built TX-by-TX.
The same TX at block 47 vs 48 produces a different cumulative state hash at
every subsequent block. Feature 21 validates state hashes during chain switches
— mismatch causes **silent failure**: the switch doesn't happen, the peer gets
suspended, and nodes remain permanently split.

**This is a hard-fork-equivalent failure**: the network splits and cannot
self-heal. On mainnet this is catastrophic — chain data cannot be wiped.

### Affected Files

| File | Location | Issue |
|------|----------|-------|
| `TxStateSnapshotHashBuilder.scala` | `node/src/main/scala/com/decentralchain/state/` | Lines 100–102 include `nextCommittedGenerators` in the per-TX hash |
| `Keys.scala` | `node/src/main/scala/com/decentralchain/database/` | `committedGenerators` key includes `commitmentHeight` |
| `RocksDBWriter.scala` | `node/src/main/scala/com/decentralchain/database/` | Stores generators with height as part of key |

### Current code (lines 100–102 of TxStateSnapshotHashBuilder.scala)

```scala
snapshot.nextCommittedGenerators.foreach { case (publicKey, blsPublicKey) =>
  changedKeys += publicKey.arr ++ blsPublicKey.arr
}
```

This adds the committed generator data into the **cumulative per-TX state hash**.
Because the cumulative hash depends on insertion ORDER (TX position in the chain),
the same logical commitment at a different block height produces a different hash.

---

## 3. Required Protocol Fix

### Principle

The state hash should commit to **what** validators are committed for a period,
not **when** they committed. The committed validators for period N are finalized
at block `N * periodLength`. The state hash should capture this final set once,
at the period boundary, not incrementally per TX.

### Implementation Plan

#### Step A — Remove CommitToGeneration from per-TX hash

In `TxStateSnapshotHashBuilder.scala`, **remove lines 100–102**:

```scala
// REMOVE THIS:
snapshot.nextCommittedGenerators.foreach { case (publicKey, blsPublicKey) =>
  changedKeys += publicKey.arr ++ blsPublicKey.arr
}
```

This makes CommitToGeneration TXs position-independent in the state hash.
Financial state (balances, scripts, leases) is still fully protected by the hash.

#### Step B — Add period-canonical committed generators hash to block header

At the last block of each generation period (block `N * periodLength`), compute
a deterministic hash of the **final committed validator set for period N+1**:

```scala
// Pseudocode — add to BlockHeader at period boundaries
val committedGeneratorsHash: Option[ByteStr] =
  if (height % periodLength == 0) {
    val validators = blockchain.committedGenerators(currentPeriod.next)
      .sortBy(_._1.toString)  // deterministic ordering
      .map { case (addr, blsKey) => addr.bytes ++ blsKey.arr }
    Some(TxStateSnapshotHashBuilder.createHash(validators))
  } else None
```

Include this hash in the `BlockHeader` and validate it during block application.

#### Step C — Validate at period boundaries only

The block appender should verify the `committedGeneratorsHash` field at period
boundary blocks. This ensures consensus on the validator set without being
sensitive to individual TX timing.

#### Why This Is Safe

- **Financial state** remains fully protected by the per-TX cumulative hash
- **Validator set** is committed to at period boundaries (immutable by then)
- **TX timing** no longer matters — same validator committing at block 47 vs 48
  produces the same period-boundary hash
- **Backward compatible** — the per-TX hash change doesn't affect non-CommitToGeneration TXs
- **Works on existing chains** — no genesis change required

### Security Analysis

The current design assumes that CommitToGeneration state is critical enough to
protect per-TX. In practice:

1. The committed validators are ALSO verified via BLS signatures in T0 endorsements
   (independent of the state hash)
2. The state hash's primary purpose is protecting **financial state** from
   undetected divergence during chain switches
3. Period-boundary validation provides the same security guarantee for validator
   sets with less coupling to TX timing

---

## 4. Operational Safeguard (Interim)

Until the protocol fix is implemented, the `commit-to-generation` workflow
enforces a **convergence check** before submitting CommitToGeneration TXs:

```bash
# Poll all nodes' last block ID every 30s, up to 30 minutes
# Only proceed when main node, gen-0, and gen-1 all share the same last block ID
```

**This is a safeguard, not a fix.** It reduces the failure window to near-zero
but cannot guarantee correctness if nodes partition after the check passes.

The protocol fix (Steps A–C above) is required for mainnet upgrade safety.

---

## 5. Implementation Priority

| Item | Status | Required for Mainnet Upgrade |
|------|--------|------------------------------|
| Convergence check in commit-to-generation workflow | ✅ Done | Interim only |
| Stagenet test harness setup | ⬜ TODO | Yes — must validate before mainnet |
| Remove CommitToGeneration from per-TX hash (Step A) | ⬜ TODO | Yes — protocol fix |
| Period-boundary committedGeneratorsHash in BlockHeader (Step B) | ⬜ TODO | Yes — protocol fix |
| Block appender validation at period boundaries (Step C) | ⬜ TODO | Yes — protocol fix |
| Stagenet validation run (Phase 1–3) | ⬜ TODO | Yes — must pass before mainnet |

**The mainnet upgrade MUST NOT proceed until:**
1. Steps A–C are implemented and tested
2. The stagenet test harness runs successfully (legacy → modern handoff at 10k blocks)
3. No chain splits are observed during the handoff window

---

## 6. Related Files

- Protocol fix: `node/src/main/scala/com/decentralchain/state/TxStateSnapshotHashBuilder.scala`
- Key storage: `node/src/main/scala/com/decentralchain/database/Keys.scala`
- State persistence: `node/src/main/scala/com/decentralchain/database/RocksDBWriter.scala`
- Legacy codebase: `/Users/jourlez/Documents/Code/Blockchain/Legacy/DecentralChain-PolyRepo`
- Workflow fix: `infra/.github/workflows/commit-to-generation.yml`
