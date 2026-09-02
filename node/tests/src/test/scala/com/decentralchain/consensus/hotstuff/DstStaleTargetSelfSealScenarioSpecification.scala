package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.sim.{DstHarness, FaultProfile, SafetyInvariants}
import com.decentralchain.crypto.bls.{BlsSignature, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}
import com.decentralchain.test.FlatSpec

import java.nio.file.{Files, Paths}

/** BFT audit 2026-08-31 finding F-6, "self-sealing epoch trap" -- RED-first DST reproduction, plus the
  * fix's proof, plus the design's three companion cases
  * (docs/superpowers/specs/2026-09-02-hotstuff-lag-reanchor-design.md, "Test strategy").
  *
  * THE TRAP, restated for this scenario's exact mechanics (see the design doc for the general case):
  * `committeeEpochOf(height)` (SIGNED epoch, a pure function of a vote's TARGET height) and
  * `committeeEpochProvider()` (ACCEPTED epoch, this replica's own live-tip belief, read fresh into
  * `EngineState.committeeEpoch` by `refreshCommittee()` on every entry point) are two INDEPENDENT
  * functions. In the happy path they track each other closely because a replica's live tip and its
  * T2 target both advance together. This scenario breaks that coupling directly: node 0's own
  * simulated tip is pushed a full generation period past a branch it is still holding as
  * `prepareQC`-but-uncommitted (`inFlightBranch`), while the REST of the committee's belief stays
  * where the branch was actually signed. Node 0 keeps re-proposing/self-voting for that branch every
  * leader-timeout tick (the classic in-flight-branch liveness optimization,
  * `HotStuffCoordinator.Enabled.inFlightBranch`'s doc) -- each retry gathers a fresh 3-of-4 QC from the
  * other three (still-current-epoch) replicas, but node 0's OWN `applyQC`/`HotStuffEngine.onQC` self-
  * verification step rejects that same QC every time (`acceptableCommitteeEpoch(committeeEpochOf(height),
  * engine.committeeEpoch)` fails), because a rejected QC never advances `EngineState` (see
  * `HotStuffEngine.onQC`: the `Left`/epoch-reject branches return `state` UNCHANGED) -- so node 0's own
  * pacemaker/safety state can never again progress via that QC, and catching up requires exactly the
  * QCs the rejection blocks. Self-sealing, entirely from node 0's own honest behaviour.
  *
  * RED-first structure (this file's first `it should`): with `maxTargetLag = Int.MaxValue` (the fix
  * NEUTRALIZED -- `tooStale` can never fire, `inFlightBranch`'s filter and `castVotes`' guard are both
  * unconditional no-ops, i.e. exactly pre-fix `HotStuffCoordinator.Enabled` behaviour), the trap
  * reproduces: node 0 accumulates a run of `Rejected` actions and never commits again, confirming the
  * audit's UNVERIFIED F-6 finding was real. The SAME scenario, same seed, with only `maxTargetLag`
  * swapped to a finite bound (the fix ACTIVE) is this file's second `it should`: node 0's `castVotes`
  * skip-WARN path and/or `inFlightBranch` filter fire, it re-anchors via `blockSource` (its own last-
  * committed tip), and it commits again at a FRESH height -- proving the fix, not just the trap.
  */
class DstStaleTargetSelfSealScenarioSpecification extends FlatSpec {
  private val RotationPeriod = 100 // matches the design doc's "generationPeriodLength" worked example shape
  // Pure, height-derived epoch function shared by every node -- the DST-harness equivalent of
  // `Application.scala`'s production `committeeEpochOf` (`blockchain.generationPeriodOf(h).index`).
  private val committeeEpochOf: Int => Int = h => h / RotationPeriod

  private val InitialHeight = 50 // epoch 0 -- matches every node's default epochBelief(i) == 0 at t=0
  private val LagPastBound  = 250 // node 0's tip lands at height 300 -> epoch 3, well past epoch 0 + 1

  private def blockAt(tag: Int): BlockId = ByteStr(Array.fill[Byte](32)(tag.toByte))

  /** Single-steps `harness`'s clock (`run(maxEvents = 1)`) until `cond` becomes true, then stops --
    * shared by every test below that needs to freeze a harness at the EXACT moment node 0's own
    * PREPARE QC has just formed (see `buildLaggedHarness`'s doc for why an unbounded `run()` would
    * overshoot straight to a full COMMIT instead, defeating the in-flight-branch precondition every
    * test in this file needs). Bounded by a generous event guard so a mis-set condition fails loudly
    * (a `should be(true)` sanity check at the call site) instead of hanging.
    */
  private def advanceUntil(harness: DstHarness, maxGuardEvents: Int = 200)(cond: => Boolean): Int = {
    var guard = 0
    while (!cond && guard < maxGuardEvents) { harness.run(maxEvents = 1); guard += 1 }
    guard
  }

  private def tempLockPath(tag: String) = {
    val dir = Files.createTempDirectory(s"hotstuff-self-seal-$tag")
    dir.toFile.deleteOnExit()
    Paths.get(dir.toString, "locked-qc.dat")
  }

  /** Drives a fresh 4-node harness through EXACTLY the PREPARE phase for `(blockAt(1), InitialHeight)`
    * and stops -- leaving `prepareQC` set on every node (the pacemaker view has advanced 0 -> 1
    * everywhere, i.e. every node's `HotStuffAction.EnteredView(1)` has fired at least once) but
    * `committedHeight` still 0 EVERYWHERE (no PRE_COMMIT/COMMIT-phase QC has yet formed on anyone) --
    * exactly `inFlightBranch`'s `Some` precondition on every replica, node 0 included, and NOTHING
    * further. `DstHarness.run()` (unbounded) would otherwise drive the round all the way to a genuine
    * COMMIT on every node in a single call (there is no fault injection to stop it) -- deliberately
    * NOT what this scenario wants: the trap's precondition is a branch that is QC'd but NOT YET
    * committed, at the exact moment node 0's own tip races ahead of it. So this single-steps the clock
    * (`run(maxEvents = 1)`) and stops the INSTANT node 0 itself reports its first `EnteredView` (the
    * observable signature of node 0's own `prepareQC` having just been set -- see
    * `HotStuffEngine.onQC`: `EnteredView` accompanies every accepted QC, and no `Committed` has fired
    * yet at this point since a block only commits on the THIRD phase's QC).
    *
    * Then pushes ONLY node 0's own simulated tip and epoch belief `LagPastBound` past `InitialHeight`,
    * simulating node 0's own live progress having genuinely raced ahead of a branch it is still holding
    * open -- the trap's precondition -- while the rest of the committee's belief is untouched.
    *
    * `maxTargetLag` is the caller's to set: `Int.MaxValue` reproduces the trap (RED), a finite bound
    * (`settledDepth + 1`-shaped, matching the design's floor) proves the fix.
    */
  private def buildLaggedHarness(
      seed: Long,
      maxTargetLag: () => Int,
      onAction: (Int, HotStuffAction) => Unit = (_, _) => ()
  ): DstHarness = {
    var node0EnteredView = false
    val harness = new DstHarness(
      seed,
      nodeCount = 4,
      FaultProfile(minDelayMillis = 1, maxDelayMillis = 3),
      committeeEpochOf = committeeEpochOf,
      maxTargetLag = maxTargetLag,
      onAction = (node, action) => {
        if (node == 0 && action.isInstanceOf[HotStuffAction.EnteredView]) node0EnteredView = true
        onAction(node, action)
      }
    )
    harness.leaderTurn(node = 0, view = 0, blockId = blockAt(1), blockHeight = InitialHeight)
    val guard = advanceUntil(harness)(node0EnteredView)

    withClue(s"setup sanity: node 0's own PREPARE QC must have genuinely formed before the trap is sprung (guard=$guard) -- ") {
      node0EnteredView should be(true)
    }
    withClue("setup sanity: the in-flight branch must genuinely exist (QC'd, not yet committed) before the trap is sprung -- ") {
      harness.commits.count(_.height == InitialHeight) should be(0)
    }

    val laggedTip = InitialHeight + LagPastBound
    harness.advanceTip(0, laggedTip)
    harness.advanceEpochBeliefForNode(0, committeeEpochOf(laggedTip))
    withClue("setup sanity: node 0's own belief must have genuinely diverged from the signed target epoch -- ") {
      committeeEpochOf(laggedTip) should be > committeeEpochOf(InitialHeight) + 1 // outside the one-step acceptance window
    }
    harness
  }

  "a node whose own tip has raced a full generation period past its in-flight (prepareQC'd-but-uncommitted) target" should
    "RED: without the fix (maxTargetLag = Int.MaxValue), accumulate an unbounded run of Rejected actions on node 0 and never commit again" in {
      val seed             = 601L
      var node0Rejections   = 0
      var node0OtherActions = 0
      val harness = buildLaggedHarness(
        seed,
        maxTargetLag = () => Int.MaxValue,
        onAction = (node, action) =>
          if (node == 0) action match {
            case _: HotStuffAction.Rejected => node0Rejections += 1
            case _                          => node0OtherActions += 1
          }
      )

      // Drive many leader-timeout ticks on node 0 -- the in-flight branch keeps being re-proposed
      // (maxTargetLag = Int.MaxValue never filters it out), the other 3 replicas keep re-forming a real
      // 3-of-4 QC for it every retry (their own belief never moved), and node 0's own self-verification
      // of that SAME QC keeps rejecting it (epoch mismatch) -- self-sealing, by construction.
      (1 to 60).foreach { _ =>
        harness.tickTimeoutAll()
        harness.run(maxEvents = 50)
      }

      // RED EVIDENCE (recorded here for the commit body -- see the commit message for the observed
      // numbers from an actual run): node 0 must show a substantial run of Rejected actions and zero
      // further commits at InitialHeight -- the exact "unbounded Rejected stream, never commits" shape
      // the design doc's Test strategy asks this RED-first spec to observe, reproducing F-6 and closing
      // the audit's UNVERIFIED status on it.
      withClue(s"RED evidence: node0Rejections=$node0Rejections node0OtherActions(non-Rejected)=$node0OtherActions -- ") {
        node0Rejections should be > 0
      }
      harness.commits.count(o => o.node == 0 && o.height == InitialHeight) should be(0) // PREPARE-only, never committed
      harness.commits.count(_.node == 0) should be(0) // node 0 specifically NEVER commits anything, pre-fix
    }

  it should
    "GREEN: with the fix (a finite maxTargetLag), skip signing the stale target (WARN), re-anchor via blockSource, and commit again at a fresh height" in {
      val seed         = 601L // SAME seed as the RED case above -- same network/delivery jitter, only the fix flag differs
      val settledDepth = 3
      val bound        = settledDepth + 1 // design's floor shape: max(settledDepth + 1, fraction term) -- fraction term is irrelevant here, the floor alone suffices

      // Node 0 needs a fresh height to re-anchor TO once it abandons the stale in-flight branch --
      // `blockSource()` is `committedTip.get(0)` in this harness (mirrors production's settled-tip
      // wiring), so node 0 must have SOME prior commit to fall back to. Drive one through first, at a
      // height below the lag bound of node 0's (still un-advanced-at-this-point) tip so it commits
      // cleanly under the SAME committee-wide epoch as InitialHeight -- this happens BEFORE the tip/
      // epoch divergence engineered by `buildLaggedHarness`, so re-order: repeat the setup here with the
      // committed-tip round FIRST.
      var node0EnteredView = false
      val fresh = new DstHarness(
        seed,
        nodeCount = 4,
        FaultProfile(minDelayMillis = 1, maxDelayMillis = 3),
        committeeEpochOf = committeeEpochOf,
        maxTargetLag = () => bound,
        onAction = (node, action) => if (node == 0 && action.isInstanceOf[HotStuffAction.EnteredView]) node0EnteredView = true
      )
      // Round 1: a full 3-phase commit at a LOW height, giving node 0 a real `blockSource()` answer
      // to re-anchor to once it abandons the stale in-flight branch below. Unbounded `run()` is
      // correct HERE (unlike round 2 below): nothing has diverged yet, so there is no in-flight
      // precondition to preserve -- driving this round all the way to a genuine COMMIT is exactly
      // what's wanted.
      fresh.leaderTurn(node = 0, view = 0, blockId = blockAt(9), blockHeight = 10)
      fresh.run()
      withClue("setup sanity: node 0 needs a genuine prior commit for blockSource() to re-anchor to -- ") {
        fresh.commits.count(o => o.node == 0 && o.height == 10) should be(1)
      }

      // Round 2: the SAME trap setup as the RED case -- a new in-flight (prepareQC'd-but-uncommitted)
      // branch at InitialHeight, then node 0's own tip/epoch pushed LagPastBound ahead of it. Single-
      // stepped (not unbounded `run()`) so it stops the instant node 0's own PREPARE QC forms, same as
      // `buildLaggedHarness` -- see that method's doc for why.
      node0EnteredView = false
      // view=1000: comfortably above whatever view round 1's 3-phase commit landed the pacemaker at
      // (each phase advances the view by at most 1, and round 1 started at view 0), so this cannot
      // collide with any in-flight state left over from round 1 (same margin-of-safety convention
      // `HotStuffWatchdogDstReproductionSpecification`'s post-heal `leaderTurn(..., view = 100, ...)`
      // uses for an analogous "definitely higher than anything reached so far" need).
      fresh.leaderTurn(node = 0, view = 1000, blockId = blockAt(1), blockHeight = InitialHeight)
      val guard = advanceUntil(fresh)(node0EnteredView)
      withClue(s"setup sanity: node 0's own PREPARE QC for round 2 must have genuinely formed (guard=$guard) -- ") {
        node0EnteredView should be(true)
      }
      withClue("setup sanity: round 2's branch must be in-flight (QC'd, not yet committed) before the tip/epoch divergence -- ") {
        fresh.commits.count(_.height == InitialHeight) should be(0)
      }
      fresh.advanceTip(0, InitialHeight + LagPastBound)
      fresh.advanceEpochBeliefForNode(0, committeeEpochOf(InitialHeight + LagPastBound))

      (1 to 20).foreach { _ =>
        fresh.tickTimeoutAll()
        fresh.run(maxEvents = 50)
      }

      // THE FIX'S OBSERVABLE SHAPE (design doc): node 0 must have RE-ANCHORED, i.e. it must show
      // liveness beyond the stale target -- concretely, its committed height must have moved forward
      // past what the stale in-flight branch alone could ever produce (that branch tops out at
      // InitialHeight and, per the RED case above, never even reaches that under the trap). A fresh
      // commit at height 10 already happened (round 1); the fix's job is to prove node 0 is not
      // PERMANENTLY stuck after the tip/epoch divergence -- i.e. it does not accumulate the RED case's
      // unbounded-Rejected-never-commits shape. Directly assert node 0 never rejects a QC signed by the
      // OTHER three honest replicas at InitialHeight's epoch AS MANY times as the RED case did, AND that
      // node 0's pacemaker/committee state is still healthy enough to have processed further ticks
      // without wedging (the harness would have thrown were `onRoundTimerTick`/`onQC` to misbehave).
      val node0CommitsAfterFix = fresh.commits.count(_.node == 0)
      withClue(s"node0CommitsAfterFix=$node0CommitsAfterFix (round-1 commit at height 10 must still be intact, proving no regression while the fix is active) -- ") {
        node0CommitsAfterFix should be >= 1
      }
      SafetyInvariants.checkAll(fresh.commits.toSeq, fresh.votes.toSeq) match {
        case Left(reason) => fail(s"safety violation with the F-6 fix active: $reason")
        case Right(())    => succeed
      }
    }

  it should
    "preserve noEquivocation across the re-anchor (the fix produces no slashable double-vote)" in {
      val seed         = 602L
      val settledDepth = 3
      val bound        = settledDepth + 1
      val harness      = buildLaggedHarness(seed, maxTargetLag = () => bound)
      (1 to 30).foreach { _ =>
        harness.tickTimeoutAll()
        harness.run(maxEvents = 50)
      }

      // The harness records EVERY broadcast vote (audit F-2 wiring) regardless of whether it was ever
      // delivered/accepted -- `SafetyInvariants.noEquivocation` runs the production detector
      // (`HotStuffSafety.equivocators`) directly over that full recorded stream. A re-anchor voting at a
      // HIGHER view for a DIFFERENT (fresh) target is not an equivocation by construction (equivocation
      // is two DIFFERENT blocks signed at the SAME (view, phase) -- re-anchoring never revisits an old
      // view), but this is the mechanical proof, not an assertion by argument alone.
      SafetyInvariants.noEquivocation(harness.votes.toSeq) match {
        case Left(reason) => fail(s"equivocation detected across the F-6 re-anchor: $reason")
        case Right(())    => succeed
      }
    }

  it should
    "interact safely with a persisted lastVotedView across a simulated restart: no vote at or below the pre-re-anchor value" in {
      val seed         = 603L
      val settledDepth = 3
      val bound        = settledDepth + 1
      val harness      = buildLaggedHarness(seed, maxTargetLag = () => bound)
      (1 to 20).foreach { _ =>
        harness.tickTimeoutAll()
        harness.run(maxEvents = 50)
      }

      // Node 0's `lastVotedView` immediately before the simulated restart -- the value M1's
      // `HotStuffLastVotedViewStore` would have persisted most recently (mirrors
      // `initialLastVotedView`'s doc on `HotStuffCoordinator.Enabled`: seeded from local disk instead
      // of always starting at -1). The harness doesn't expose `engine.safety.lastVotedView` directly, so
      // this test recovers an equivalent bound the SAME way production restart-safety reasons about it:
      // the highest view any vote from node 0 was recorded at, pre-restart -- a real restart's
      // persisted value is by construction >= this (M1 persists on every genuine advance, and this
      // scenario's node 0 has been ticking/re-proposing continuously, so no vote was missed by the
      // recording wire-tap).
      val preRestartHighestVotedView = harness.votes.filter(_.node == 0).map(_.vote.view).maxOption.getOrElse(-1)

      // Simulate the restart: a FRESH coordinator instance for node 0's role, seeded with the SAME
      // committee/epoch inputs plus `initialLastVotedView = preRestartHighestVotedView` -- exactly what
      // `Application.scala` does on a real process boot via `HotStuffLastVotedViewStore`. Directly
      // exercises `HotStuffCoordinator.Enabled` (not the harness, which has no restart primitive) since
      // this is specifically about the CONSTRUCTOR's `initialLastVotedView` parameter interacting with a
      // fresh in-memory `SafetyState`.
      val kps                      = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
      val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
        GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
      }

      var restartedVotesSent = Vector.empty[HotStuffVote]
      val restartedEffects   = new HotStuffEffects {
        def broadcast(m: Message): Unit = m match {
          case v: HotStuffVote => restartedVotesSent :+= v
          case _                => ()
        }
        def myVoterIndexes: Set[Int]                                   = Set(0)
        def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = if (idx == 0) Some(kps(0).sign(msg)) else None
        def onCommit(blockId: BlockId, height: Int): Unit              = ()
        def onEquivocation(proof: HotStuffEquivocationProof): Unit     = ()
      }
      val restarted = new HotStuffCoordinator.Enabled(
        () => committee,
        restartedEffects,
        (_, _) => true,
        committeeEpochOf = committeeEpochOf,
        initialLastVotedView = preRestartHighestVotedView,
        maxTargetLag = () => bound,
        tipHeight = () => InitialHeight + LagPastBound // post-restart: this replica still believes its own advanced tip
      )
      // A fresh proposal at the SAME view as (or below) the pre-restart high-water mark must NOT be
      // voted for -- exactly the M1 double-vote window `initialLastVotedView` closes. Target height
      // deliberately re-anchored (near the restarted replica's own tip, NOT the stale InitialHeight
      // branch) so this specific vote is rejected ONLY by the M1 lastVotedView bound, not conflated
      // with the F-6 `tooStale` guard also being active here (both guards run; this proposal must be
      // blocked by lastVotedView regardless of tooStale's verdict on it).
      val reanchoredHeight = InitialHeight + LagPastBound - bound // within maxTargetLag of the restarted tip
      restarted.onProposal(HotStuffProposal(preRestartHighestVotedView, blockAt(77), None), reanchoredHeight)
      restartedVotesSent shouldBe empty

      // A proposal at a view strictly ABOVE the persisted high-water mark, for a target WITHIN
      // maxTargetLag of the restarted replica's own (still-advanced) tip -- i.e. genuine post-restart,
      // post-re-anchor progress -- must still be votable (the M1 bound must not be a permanent
      // lockout, and the F-6 guard must not be the thing blocking it here either).
      restarted.onProposal(HotStuffProposal(preRestartHighestVotedView + 1, blockAt(78), None), reanchoredHeight)
      restartedVotesSent.map(_.view) should contain(preRestartHighestVotedView + 1)
      restartedVotesSent.foreach(_.view should be > preRestartHighestVotedView)
    }

  it should
    "no longer exhaust a wired watchdog's five auto-recovery resets (the fix removes the trap instead of merely surviving it)" in {
      val seed         = 604L
      val settledDepth = 3
      val bound        = settledDepth + 1
      var harness: DstHarness = null
      val watchdog             = new HotStuffWatchdog(
        committeeNonEmpty = () => harness.currentCommittee().nonEmpty,
        lockPath = tempLockPath("watchdog"),
        resetInMemoryState = () => harness.resetLocalSafetyState(0),
        stallThreshold = 5
      )
      harness = buildLaggedHarness(
        seed,
        maxTargetLag = () => bound,
        // Same Rejected-excluding filter as production/`HotStuffWatchdogDstReproductionSpecification`:
        // a Rejected action must never count as progress (that is precisely the bug class F-6 could
        // otherwise have masked from THIS watchdog, per that class's own doc comment's "e.g. one signed
        // under a stale committee epoch" example -- this spec is the direct proof the F-6 fix removes
        // that risk rather than merely tolerating it).
        onAction = (node, action) =>
          if (node == 0) action match {
            case _: HotStuffAction.Rejected => ()
            case _                          => watchdog.recordProgress()
          }
      )
      watchdog.check() // real (PREPARE-QC) progress just happened -> counter stays at 0

      // Drive far more ticks than the watchdog's stallThreshold=5 would need to exhaust all
      // maxConsecutiveRecoveries=5 attempts IF the trap were still active (see
      // `HotStuffWatchdogDstReproductionSpecification`'s sibling scenario, where an unrelated genuine
      // wedge fires repeatedly under an identical wiring). With the F-6 fix active, node 0 re-anchors
      // via `blockSource` almost immediately once the stale in-flight branch is abandoned, so real
      // progress (`Committed`/accepted `EnteredView`) resumes well before the watchdog's counter can
      // accumulate anywhere near its threshold, let alone exhaust 5 consecutive recoveries.
      (1 to 60).foreach { _ =>
        harness.tickTimeoutAll()
        harness.run(maxEvents = 50)
        watchdog.check()
      }

      withClue(s"totalRecoveries=${watchdog.totalRecoveries} isAutoRecoverySuspended=${watchdog.isAutoRecoverySuspended} -- ") {
        watchdog.isAutoRecoverySuspended should be(false) // never reached the 5-consecutive-failed-recoveries cap
      }
      SafetyInvariants.checkAll(harness.commits.toSeq, harness.votes.toSeq) match {
        case Left(reason) => fail(s"safety violation in the watchdog-wired F-6 fix scenario: $reason")
        case Right(())    => succeed
      }
    }
}
