# DCC Finality — Adversarial Consensus Audit

**Date:** 2026-07-19 · **Scope:** T0 DeterministicFinality (feature 25, authoritative) + T2 HotStuff overlay (advisory).
**Method:** four hostile auditors, each tasked to *break* a specific property (safety/double-finalization, BLS/quorum forgery, liveness/stall, deposit/economic), tracing the actual code; plus independent verification of every surviving finding against the source.

> **Independence caveat:** this is a rigorous internal adversarial audit — it is the technical substance of an audit, and it found and fixed a real HIGH-severity break. It does **not** substitute for an independent third-party firm's sign-off (the same author shaped the code), which remains recommended for liability/trust before HotStuff is made authoritative or before public mainnet.

---

## Result: 1 HIGH found and FIXED; everything else holds

| Attack class | Verdict |
|---|---|
| **Finalized block reverted by reorg** | **WAS EXPLOITABLE → FIXED** (finality barrier now enforced) |
| Double-finalization (2/3 threshold math) | SOUND (BigInt, no double-count, conflict-excluded) |
| BLS rogue-key / quorum forgery | SOUND (PoP enforced, subgroup+infinity, FastAggregateVerify) |
| Endorsement replay / cross-fork reuse | SOUND (parent-bound message) |
| Liveness / cheap stall / griefing | SOUND (every halt path needs >1/3 stake; RC#2 closed) |
| Deposit theft / double-spend / slash-evasion | SOUND (computed virtual lock; height-aligned burn) |
| HotStuff conflicting QCs | MITIGATED (advisory-only; canonical-filter) |

---

## HIGH — Finalized history was not protected from reorg  →  FIXED

**Finding.** `finalizedHeight` was only *advertised* in `lastBlockIds` (`Blockchain.scala:133`, a request-side hint honest peers respect). The fork-adoption path did **not** enforce it: `ExtensionAppender` adopts any `remoteScore > localScore` fork and calls `removeAfter`, whose only guard is `safeRollbackHeight = height − maxRollbackDepth (2000)` (`BlockchainUpdaterImpl.scala:514`, `RocksDBWriter.scala:521`) — it never compares against `finalizedHeight`. `RxExtensionLoader.onNewSignatures` does not reject a fork whose common point is below the advertised floor. **Net:** a higher-score fork branching below `finalizedHeight` (within 2000 blocks) — deliverable by a majority-stake adversary, or to an eclipsed/partitioned node — reverted finalized blocks, defeating the core irreversibility guarantee of the finality feature.

**Fix.** `ExtensionAppender` now enforces the finality floor as a hard barrier: an incoming fork is rejected if its common block is below `max(finalizedHeight, height − maxRollback)` — the same floor `lastBlockIds` advertises. Legitimate tip reorgs (above finalized) and the operator `/debug/rollback` path are unaffected; only deep below-finalized reorgs are rejected.

**Verification.** JVM: `ExtensionAppenderSpec` (incl. a new test that a higher-score fork branching below the floor is rejected), `BlockChallengeTest`, `LightNodeTest`, finalization specs — all green. node-it: `RollbackSuite` (rollback + resync) + `NNodesRotatingFinalizationTestSuite` (finality under rotation) green on a rebuilt image — the barrier does not break legitimate reorg/resync/finality.

---

## Verified SOUND (independently confirmed against source)

- **Threshold math** — `isFinalized = endorsedBalance*3 >= totalBalance*2` in **BigInt** (`FinalizationVoting.scala:45`); the fraction/rounding case is documented and correct. Each generator counted at most once (miner-OR-endorser) with conflict indices excluded from both numerator and denominator (`FinalizationState.isParentFinalized`). No path finalizes two conflicting blocks at one height without >1/3 equivocation, which is slashed.
- **BLS crypto** — proof-of-possession enforced at commit (`commitmentSignature.verifyBasic(popMessage, endorserPublicKey)`) + subgroup (`in_group()`) + infinity (`is_inf()`) checks (`BlsUtils.scala:88-92`); `verifyAgg` is FastAggregateVerify over one common message. Rogue-key forgery mitigated. PoP runs even on `verify=false` import paths — no bypass.
- **Message binding** — endorsers sign `finalizedId ++ height ++ endorsedId`; the validator re-derives `finalizedId`/`endorsedId` from canonical chain state, so endorsements can't be replayed across heights/forks. HotStuff votes are domain-separated (`"DCCHOTSTUFF "` prefix) from T0.
- **Liveness** — no sub-1/3 stall path; the `tip − maxRollback` fallback keeps a moving finalized floor without gating block production; the rebroadcast fix closes the RC#2 fire-once/rotation stall.
- **Deposit economics** — the 100 DCC deposit is a computed virtual lock subtracted from both effective and spendable balance; release is height-aligned with the slash burn (`conflict.hasInUpTo(at.prev)`), so it cannot be double-spent or its slashing evaded; supply stays in lockstep.

---

## Hardening applied (from the auditors' notes)

- `FinalizationState`: `totalBalance > 0` guard so a fully-conflicted committee can't vacuously finalize (`isFinalized(0,0)`).
- `HotStuffVoteCollector.tryFormQC`: `Long → BigInt` threshold math, consistent with `HotStuffQC.meetsThreshold` (advisory path; no overflow at DCC scale, but removes the inconsistency).
- Corrected a stale `maxCommittedGenerators` comment.

## Non-exploitable items — recommended for the external pass / future hardening

1. **Dedicated PoP domain-separation tag** — the proof-of-possession reuses the ordinary signing DST rather than the IETF `_POP_` DST (acknowledged in-comment). No exploit (message shapes can't collide), but the standard construction is preferable.
2. **Chain-id / network domain separator** in `BlockEndorsement.mkMessage` — prevents any cross-network endorsement reuse if BLS keys are ever shared across DCC networks (block-id collision is cryptographically implausible, so defense-in-depth only).
3. **Per-peer rate limit before BLS verification** of incoming endorsements — generic P2P CPU-spam bound, not a finality bug.
4. **Rebroadcast cadence** — fixed 3s; if mainnet block time drops below that, make it a fraction of block time.
5. **Explicit negative-balance assertion** on the slash path (`createInitialBlockSnapshot`) — currently safe by the lock invariant; an assertion would catch future regressions to release-timing.

## Bottom line
The authoritative T0 finality is now consensus-safe **including reorg-revert**, which it previously was not. The one real break the adversarial audit surfaced is fixed and verified at unit + integration level. T2 HotStuff remains advisory pending its BFT-lock redesign + external audit. The residual items are defense-in-depth, none exploitable.
