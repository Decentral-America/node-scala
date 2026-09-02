# F-6: Bound T2 target lag; re-anchor near the tip — Design

> Source: BFT audit 2026-08-31 finding F-6 (self-sealing epoch trap). Design pass 2026-09-02.
> NOT consensus-breaking: votes/QCs/target selection are local/ephemeral; nothing changes on the
> wire or in block validation. Independently deployable; no feature gate.

## The trap, restated precisely from the code

- **Signed** epoch is a pure function of target height: `committeeEpochOf` =
  `generationPeriodOf(targetHeight).index` (`Application.scala`), consumed in
  `HotStuffCoordinator.castVotes`.
- **Accepted** epoch is a function of the replica's **live tip**: `committeeEpochProvider` =
  `currentGenerationPeriod.index`, refreshed by `refreshCommittee()`, compared by
  `HotStuffQuorum.acceptableCommitteeEpoch` (accepts only `currentEpoch` or `currentEpoch - 1`).

`settledDepth = 3` keeps them within 3 blocks in the happy path, but nothing bounds the gap. If
T2's target falls more than one generation period behind the tip,
`acceptableCommitteeEpoch(e, e+2) == false` and `HotStuffEngine.onQC` rejects the replica's own
honest QCs before `verifyQC`. Catching up requires committing those heights, which requires
accepting those QCs. Self-sealing. The watchdog cannot help: `resetLocalSafetyState` touches only
`engine.safety`; the epoch mismatch is derived from chain height.

## Where the lag actually lives

`blockSource` and the leader-turn path both compute `tip - settledDepth` from the live tip — they
are never stale. The only holder of a stale target is `inFlightBranch`, derived from
`engine.safety.prepareQC`, which advances monotonically by view and is never aged by height.
`MaxConsecutiveReproposeAttempts` bounds consecutive same-blockId attempts, not height lag.

> **F-6's re-anchor is a height-lag filter on `inFlightBranch`, plus a defensive lag check on the
> vote path. Nothing else.**

## Design

1. **New setting** in `HotStuffSettings`:
   `maxTargetLagFraction: Double = 0.25` with
   `require(maxTargetLagFraction > 0 && maxTargetLagFraction < 1, ...)` inside the existing
   `if (enabled)` block. Fraction of `generationPeriodLength`.

2. **Providers on `HotStuffCoordinator.Enabled`** (additive, behavior-preserving defaults, the
   class's established convention):
   ```scala
   // F-6: max blocks a T2 target may lag the live tip before this replica abandons it and
   // re-anchors near the tip. Int.MaxValue default = no bound = today's exact behaviour.
   maxTargetLag: () => Int = () => Int.MaxValue,
   // The replica's live tip height. 0 default with the lag check disabled = no-op.
   tipHeight: () => Int = () => 0,
   ```
   Production wiring in `Application.scala`:
   ```scala
   val maxTargetLag: () => Int = () => math.max(
     settings.hotStuffSettings.settledDepth + 1,
     (generationPeriodLength * settings.hotStuffSettings.maxTargetLagFraction).toInt
   )
   val tipHeight: () => Int = () => blockchainUpdater.height
   ```
   The `max(settledDepth + 1, ...)` floor guarantees the bound can never be tighter than the happy
   path's structural lag (else the node would abandon every target and never progress). NOTE: live
   testnet runs `generation-period-length = 100` (infra dcc.conf), so the fraction term is 25
   there — the floor matters.

3. **The lag filter — one predicate, two use sites** in `HotStuffCoordinator.Enabled`:
   ```scala
   private def tooStale(height: Int): Boolean = tipHeight() - height > maxTargetLag()
   ```
   *Use site A — `inFlightBranch`*: add `.filterNot(qc => tooStale(qc.blockHeight.toInt))` so a
   stale in-flight branch is never re-proposed and the tick falls through to `blockSource()`'s
   fresh tip. WARN log when the filter fires ("stale in-flight branch abandoned, re-anchoring") —
   distinct from the `MaxConsecutiveReproposeAttempts` WARN. Reset
   `lastReproposedBlockId`/`reproposeAttempts` when it fires (same rationale as the existing reset).
   *Use site B — `castVotes`, defensive*: before signing, check the epoch this replica would sign
   is one it would itself accept:
   ```scala
   val epoch = committeeEpochOf(height)
   if (!HotStuffQuorum.acceptableCommitteeEpoch(epoch, engine.committeeEpoch)) {
     logger.warn(s"[HotStuff] castVotes SKIPPED: target height $height signs epoch $epoch, " +
                 s"outside my acceptance window (current=${engine.committeeEpoch}) -- stale " +
                 s"target, awaiting re-anchor (audit F-6)")
   } else { /* existing body */ }
   ```
   Same predicate the rejection uses — "don't sign what I would reject". Catches any stale-target
   route not enumerated, including a stale externally-supplied `onLeaderTurn`.

4. **What state resets on re-anchor: NOTHING.** Do not touch `safety.lockedQC`,
   `safety.lastVotedView`, or `voted`:
   - `lastVotedView` is monotonic, persisted (M1), and preserved by `resetLocalSafetyState` —
     clearing it would be a slashing bug under feature 29. Re-anchoring to a higher tip means
     voting at a higher view, which the existing bound permits natively — a re-anchor cannot
     produce a double-vote by construction.
   - `voted` is SAFETY-LOAD-BEARING (and now pruned by view per audit F-9 — that pruning is
     orthogonal and sufficient).
   - `lockedQC` is superseded naturally by higher-view QCs from the re-anchored target
     (`HotStuffSafety.update`).
   The fix lives entirely in liveness/target-selection; the safety layer is untouched.

5. **Do NOT widen `acceptableCommitteeEpoch`.** The one-step window is the T10 fix's safety
   content (`HotStuffCrossEpochForkSpecification`). Untouched.

6. **Observability**: Kamon counter (e.g. `hotstuff.stale-target-abandoned`) alongside the WARN at
   use site A, so the trap's occurrence is graphable — this is what converts the audit's
   UNVERIFIED F-6 into observed-and-fixed.

## Test strategy — including the DST harness change the audit asked for

Harness (`node/tests/.../consensus/hotstuff/sim/DstHarness.scala`):
1. Replace the shared `epochBelief` var with a per-node simulated tip
   (`simulatedTip: mutable.Map[Int, Int]`), deriving each node's epoch belief from ITS OWN tip via
   the same period arithmetic as `committeeEpochOf`.
2. Keep `advanceEpochBelief` as a thin shim setting every live node's simulated tip, so all
   existing scenarios keep byte-for-byte behavior.
3. Add `advanceTip(node: Int, height: Int)` so a scenario can push one node's tip a full period
   past the target it is voting on.
4. Wire the new coordinator params: `tipHeight = () => simulatedTip(i)`, scenario-controlled
   `maxTargetLag`.

Specs:
- **`DstStaleTargetSelfSealScenarioSpecification` (RED-first)**: without the fix, a node whose tip
  outruns its target by > 1 period emits an unbounded run of `Rejected` actions and never commits
  (reproduces F-6, closing the audit's UNVERIFIED); with the fix, it skips signing (WARN),
  re-anchors via `blockSource`, and commits at the fresh height.
- **`noEquivocation` across the re-anchor** (harness already records every broadcast vote;
  `SafetyInvariants.noEquivocation` runs directly) — mechanical proof the re-anchor produces no
  slashable double-vote.
- **Persisted `lastVotedView` interaction**: re-anchor, simulate restart seeding
  `initialLastVotedView` from the pre-re-anchor value; assert no vote at or below it.
- **Watchdog interaction**: the F-6 scenario with a watchdog wired no longer exhausts its five
  resets — the fix removes the trap rather than surviving it.
- **Unit**: `tooStale` boundaries; the `settledDepth + 1` floor under a pathologically small
  fraction; settings `require` rejection.

## Rejected alternatives

- Widen `acceptableCommitteeEpoch` — reopens the T10 fork hazard; ruled out by the audit.
- Watchdog performs the re-anchor — wrong contract (isolation-proven), and ties recovery to a
  38-minute backoff schedule.
- Wall-clock aging of `prepareQC` — non-deterministic, wrong metric (height lag is what the epoch
  derivation depends on).
- Derive the ACCEPTED epoch from the target height (make both sides pure) — the tip-derived
  acceptance is deliberate: a QC must not self-authorize its own epoch.

## Blast radius

Two constructor params (behavior-preserving defaults), one private predicate, one `filterNot`, one
guard in `castVotes`, one setting, ~6 lines of Application wiring, plus the harness change (larger
than the production change — the shim keeps existing scenarios untouched; review that carefully).
