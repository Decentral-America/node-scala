# Waves upstream bug: committee-list state-hash divergence on microblock discard

Status: **internal record only — not yet reported to Waves.** This document is Step 1/3 of a
three-step process (see `docs/superpowers/plans/2026-08-30-testnet-final.md` Task E); Step 2
(deciding how/whether to submit this externally — GitHub issue on `wavesplatform/Waves`, direct
contact, or staying internal-only) requires explicit user sign-off that has not happened yet. Do
not submit anything externally based on this document without that sign-off.

This is the sibling bug to the one fixed in DCC's own codebase by
`docs/consensus-divergences-from-upstream.md` §3 (Task D in this repo's plan). Full background,
proof methodology, and cross-references: `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §9, "Bug 1."

## Summary

A block is built incrementally in Waves-NG via a sequence of small, live "microblocks." When a
microblock is discarded — a small fork at the microblock level, where the chain ends up building
on a different microblock (or a key block that skips it) instead — everything about that
microblock is supposed to be undone. One piece of state is not: the in-memory list of which
validators are currently committed ("committed generators"). That list stays edited to whatever
it was immediately before the discard.

That list is folded directly into the state hash. Two nodes that end up agreeing on the exact
same final chain can each be holding a different version of the list — depending on whether they
happened to briefly observe the now-discarded microblock — and so compute two different state
hashes for what is, on the wire, the identical block.

## Proof

Built and ran a real test against Waves' own code, in an isolated local copy, no live network
involved. Two identical final chains — same blocks, same transactions, same order — produced two
different computed state hashes for the same block, differing only in whether a since-discarded
microblock's committee-list edit was briefly visible to the node computing the hash. Confirmed
twice (repeatable, not a one-off race).

## Exact mechanism, with file/line references (Waves' current codebase)

1. **The live, in-memory cache that never rolls back on microblock discard:**
   `node/src/main/scala/com/wavesplatform/database/Caches.scala`
   - `committedGeneratorsCache` (line 220): `private var committedGeneratorsCache = Map.empty[GenerationPeriod, IndexedSeq[(Address, BlsPublicKey)]]`.
   - Read path, `committedGenerators(at: GenerationPeriod)` (lines 221–227): serves whatever is
     currently in the cache for that period.
   - Write path (lines 372–375, inside `append`): every time a block carries a non-empty
     `nextCommittedGenerators`, the cache is unconditionally updated —
     `committedGeneratorsCache = committedGeneratorsCache.updatedWith(currPeriod.next) { orig => Some(orig.getOrElse(Vector.empty) ++ nextCommittedGeneratorsWithAddr) }`.
     Nothing here is keyed to, or reversible against, any specific microblock/liquid-block
     identity — it is a flat, period-indexed accumulator.
   - The cache is only ever reset at two points: a period boundary (lines 472–475, filtering keys
     `>= currPeriod`) or a full chain rollback (`rollbackTo`, line 495: `committedGeneratorsCache = Map.empty`).
     Neither of these fires on an ordinary microblock-level discard within the current liquid
     period — the exact gap.

2. **Where the stale value gets folded into the state hash:**
   - Block-level: `Caches.scala` lines 432–433 —
     `snapshot.nextCommittedGenerators.foreach(stateHash.addNextCommittedGenerator)` and
     `stateHash.addCommittedGeneratorBalances(generatorSet.sortBy(_.index).map(_.balance))`.
   - Per-transaction level: `node/src/main/scala/com/wavesplatform/state/TxStateSnapshotHashBuilder.scala`
     line 100 — `snapshot.nextCommittedGenerators.foreach { case (publicKey, blsPublicKey) => ... }`
     folds the same data into the per-TX hash as each `CommitToGenerationTransaction` is processed.
   - The carrier field itself, `nextCommittedGenerators: Seq[(PublicKey, BlsPublicKey)]`, is
     defined and monoid-combined (`s1.nextCommittedGenerators ++ s2.nextCommittedGenerators`,
     never subtracted) in `node/src/main/scala/com/wavesplatform/state/StateSnapshot.scala`
     (lines 36, 91, 117, 236) — combination is append-only by construction, with no mechanism to
     remove a discarded microblock's contribution once merged.

3. **The liquid-state accumulation point that produces the stale snapshot in the first place:**
   `node/src/main/scala/com/wavesplatform/state/NgState.scala` line 163–166, inside the
   microblock-append path (`updatedGeneratorSet: GeneratorSet` threaded into
   `finalizationState.append(fixedTotalBlockId, microBlock.finalizationVoting, updatedGeneratorSet)`).
   This is the same liquid-append call site where the sibling `finalizedHeight` bug (Task D, fixed
   in DCC) originates — both bugs are two different pieces of state accumulated at the same
   microblock-append point, neither one keyed per-microblock-id, so neither one is individually
   reversible when a specific microblock is later dropped in favor of a competing fork.

## Why this is a real, if currently low-severity, disagreement risk

- Cannot be used to steal funds or forge a transaction — the committee list is validator-set
  bookkeeping, not a balance or authorization check.
- Can plausibly be used to make two honest nodes compute different hashes for the same block,
  causing one or both to reject a chain switch they should accept — a liveness/DoS-class risk,
  not an access-control one.
- Confirmed reproducible locally; no live-network exploitation has been observed or attempted.

## DCC's exposure: none, but by accident, not by design

DCC's `TxStateSnapshotHashBuilder.scala`/`Caches.scala` exclude `nextCommittedGenerators`/
`CommittedGeneratorBalances` from both the per-TX and block-level state hash entirely — see
`docs/consensus-divergences-from-upstream.md` §3. That exclusion was made for an unrelated reason
(DCC's own PR #53, motivated by a real DCC-side peer-suspension incident, not by this bug) and
happens to also sidestep this exact defect. DCC never fixed the underlying flaw (the live
`committedGeneratorsCache`/`nextCommittedGenerators` accumulation still doesn't roll back on
microblock discard in DCC's inherited code either, unmodified from Waves) — DCC just built a
hash that never needs to ask that flawed value a question. If any future DCC code path starts
reading `committedGenerators`/`nextCommittedGenerators` for a new purpose (eligibility checks,
voting weight, anything beyond hashing), it would inherit this exact exposure. Confirmed via
direct code check that no current DCC code (HotStuff/T2 included) reads this value for anything
other than hashing today.

## Fix shape (not built, Waves' code to fix — described for the report)

Mirror the approach DCC used to fix the sibling bug (Task D, `finalizedHeight`/`FinalizationState`
rollback) in `NgState.scala`: key the committee-list state to the specific microblock (liquid
block) it was produced under, rather than accumulating it in a single flat, period-indexed
mutable cache. Concretely:

- Add a `Map[BlockId, <committee snapshot at this point>]` alongside `NgState`'s liquid-block
  bookkeeping (DCC's equivalent is `finalizationSnapshots: Map[BlockId, FinalizationState]`,
  `node/src/main/scala/com/decentralchain/state/NgState.scala` lines 82–83, populated at line 189
  and read via `finalizationStateFor` at lines 205–206).
- When a microblock is discarded, simply don't carry its entry forward — since each microblock's
  contribution is keyed by its own id rather than merged in place, a discarded microblock's
  contribution is never visible to whichever block ends up referencing an earlier point in the
  liquid chain.
- Replace `Caches.scala`'s unconditional `committedGeneratorsCache = committedGeneratorsCache.updatedWith(...)` accumulation with a read that resolves the correct snapshot for the block
  actually being persisted, the same way DCC's `finalizationStateFor(totalBlockId)` resolves the
  correct `FinalizationState` for the block actually being forged instead of always reading the
  latest global state.

This is the shape of the fix Waves would need; it has not been implemented anywhere (not in
Waves, not in DCC — DCC's fix for this data is the hash-exclusion described above, not a rollback
fix).

## Reported to Waves? No, not yet.

Checked Waves' public GitHub issues directly (not assumed): nothing matches this symptom. As far
as can be determined, Waves is unaware of this defect. No external report has been filed. Step 2
of this task (deciding how/whether to report — GitHub issue, direct contact, or internal-only)
is pending explicit user sign-off.
