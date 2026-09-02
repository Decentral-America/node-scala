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

/** BFT audit 2026-08-31 finding F-6, "self-sealing epoch trap" -- RED/GREEN DST reproduction plus the
  * fix's proof (docs/superpowers/specs/2026-09-02-hotstuff-lag-reanchor-design.md, "Test strategy").
  *
  * THE TRAP: `committeeEpochOf(height)` (the epoch a vote is SIGNED under -- a pure function of the
  * vote's TARGET height) and `committeeEpochProvider()` (the epoch a replica ACCEPTS -- derived from its
  * OWN live tip, read fresh into `EngineState.committeeEpoch` by `refreshCommittee()`) are two
  * INDEPENDENT functions. `settledDepth` keeps them close in the happy path, but nothing bounds the gap.
  * Once a T2 target falls far enough behind, a replica's own honest QCs for it are rejected by
  * `HotStuffQuorum.acceptableCommitteeEpoch` before `verifyQC` even runs, and a rejected QC never
  * advances `EngineState` -- so catching up requires committing exactly the heights the rejection
  * blocks. Self-sealing, from fully honest behaviour.
  *
  * ==Why this file is staged the way it is (RESTAGED 2026-09-02 after review)==
  *
  * The FIRST version of this spec was VACUOUS in its fixed arm: setting the coordinator bound to
  * `Int.MaxValue` (neutralizing the fix entirely) left all four fixed-arm tests GREEN. The reason is
  * worth recording, because it is the trap for anyone restaging this again: that version diverged only
  * ONE node's tip, and node 0's rejections were INBOUND QCs arriving from the other three replicas via
  * `applyQC` -- a path `tooStale` deliberately does NOT gate (the fix is a filter on this replica's OWN
  * target selection, not an inbound-QC gate). Node 0 also never led a stalled tick while holding a stale
  * `inFlightBranch`, so the fix's primary use site was never even reached. The arm's only assertion
  * (`node0Commits >= 1`) was satisfied by a commit that happened BEFORE the divergence was staged, so
  * the fix could not possibly have influenced it.
  *
  * This restaging uses the REALISTIC F-6 shape the audit describes instead: the WHOLE cluster's tips
  * advance past the stuck T2 target together (`advanceTipAndEpochAll`) -- which is what actually happens
  * in production, since feature-25 finality keeps the chain advancing underneath a stalled T2 round, on
  * every replica at once. Every replica then self-rejects its own honest QCs for the stale target, so no
  * quorum of accepting replicas exists ANYWHERE and ONLY re-anchoring can restore commits. That makes
  * the fix load-bearing on the OUTCOME, not merely present:
  *
  *   - RED arm (`maxTargetLag = Int.MaxValue`, fix neutralized): the cluster re-proposes the stale
  *     in-flight branch and commits it at `InitialHeight` -- the trap, no re-anchor, ever.
  *   - GREEN arm (finite bound, fix active): the stale branch is abandoned, the cluster re-anchors to
  *     its fresh settled tip, and commits at a height STRICTLY ABOVE `InitialHeight`.
  *
  * MUTATION-VERIFIED (2026-09-02, re-run after restaging). Neutralizing the fix BEHAVIORALLY -- passing
  * `maxTargetLag = () => Int.MaxValue` to the coordinator in every fixed arm, while leaving `Bound`
  * itself intact so the setup sanity checks still hold -- turns every fixed-arm test RED:
  *
  *   - GREEN arm: FAILED, `newCommitHeights=List(50)` (RED's were also `List(50)`), "50 was not greater
  *     than 50" -- i.e. without the fix the cluster commits the STALE target instead of re-anchoring.
  *   - noEquivocation: FAILED, "must be checked across a REAL re-anchor" -- `reanchorFired` was false.
  *   - watchdog: FAILED, GREEN reached suspension exactly like RED (`lastCommit=50` in both arms).
  *   - RED arm: still passes, correctly -- it asserts the TRAP, which the mutation does not remove.
  *   - M1 interaction check: still passes, consistent with its scope note below (it is deliberately
  *     NOT re-anchor coverage).
  *
  * That is the property the first version lacked, and the reason each assertion below is written
  * against a signal the fix must actually PRODUCE -- a strictly-higher commit height, plus the
  * stale-abandon counters -- rather than against an outcome a neutralized fix could also reach.
  *
  * ==One staging constraint worth knowing==
  *
  * The cluster-wide tip advance stays INSIDE one generation period (`InitialHeight` -> `LaggedTip`, both
  * epoch 0). It must: a re-proposal carries this replica's `prepareQC` as its `justify`, and
  * `HotStuffEngine.onProposal` rejects the WHOLE proposal when that justify's epoch is outside the
  * acceptance window (`justifyEpochOk`). Pushing tips a full period ahead makes even the FRESH
  * re-anchored proposal unvotable for that unrelated reason, so both arms stall identically and the
  * scenario measures nothing (observed directly while restaging). Height lag alone -- `LaggedTip -
  * InitialHeight` far exceeding the bound, same epoch -- is exactly what `tooStale` tests, and suffices.
  */
class DstStaleTargetSelfSealScenarioSpecification extends FlatSpec {
  private val RotationPeriod               = 100
  private val committeeEpochOf: Int => Int = h => h / RotationPeriod

  private val InitialHeight = 50 // the T2 target that gets stuck; epoch 0
  private val PriorHeight   = 10 // a genuine commit before anything diverges; epoch 0
  // The cluster's tips end up here: 45 blocks past the stuck target -- far outside any sane
  // `maxTargetLag`, but still epoch 0, for the `justifyEpochOk` reason in this class's doc.
  private val LaggedTip    = InitialHeight + 45
  private val SettledDepth = 3
  // What the cluster re-anchors TO: its fresh settled tip, `SettledDepth` behind the live tip, exactly
  // production's `blockSource` shape. Strictly above `InitialHeight`, which is the whole point.
  private val ReanchorHeight = LaggedTip - SettledDepth // 92
  private val Bound          = SettledDepth + 1         // the design's `max(settledDepth + 1, ...)` floor
  private val Seed           = 601L

  private def blockAt(tag: Int): BlockId = ByteStr(Array.fill[Byte](32)(tag.toByte))

  private def tempLockPath(tag: String) = {
    val dir = Files.createTempDirectory(s"hotstuff-self-seal-$tag")
    dir.toFile.deleteOnExit()
    Paths.get(dir.toString, "locked-qc.dat")
  }

  /** What one arm observed, so RED and GREEN are compared on identical measurements instead of each
    * asserting its own ad-hoc shape.
    */
  private case class ArmResult(
      newCommitHeights: Seq[Int], // distinct heights committed AFTER the tips diverged
      staleAbandoned: Int,        // cluster-wide `inFlightBranch`-filter firings (fix use site A)
      staleSkipped: Int,          // cluster-wide `onProposal`-gate firings (fix use site B-primary)
      rejections: Int,            // cluster-wide `Rejected` actions after the divergence
      harness: DstHarness
  ) {
    def maxNewCommitHeight: Int = newCommitHeights.maxOption.getOrElse(-1)
    def reanchorFired: Boolean  = staleAbandoned + staleSkipped > 0
  }

  /** Stages the trap on a fresh 4-node cluster and runs it, returning what was observed.
    *
    *   1. A genuine 3-phase commit at `PriorHeight` -- real history, nothing diverged yet.
    *   2. An in-flight PREPARE QC at `InitialHeight`, single-stepped so it is QC'd but NOT committed --
    *      `inFlightBranch`'s `Some` precondition, on every replica.
    *   3. THE DIVERGENCE: every replica's own live tip (and epoch belief) jumps to `LaggedTip`, and
    *      every replica's settled tip -- what `blockSource` answers, i.e. what it can re-anchor TO --
    *      moves to `ReanchorHeight`. Both cluster-wide, the realistic shape (see class doc).
    *   4. Leader-timeout ticks. `onRoundTimerTick` only self-drives a leader when it sees a STALLED view
    *      (`lastTickView == current view`), which needs a tick that does not itself advance the view --
    *      hence back-to-back PAIRS. Without that, no replica reaches the leader path at all and
    *      `inFlightBranch` is never consulted (a real trap found while restaging).
    */
  private def runArm(
      seed: Long,
      maxTargetLag: () => Int,
      onAction: (Int, HotStuffAction) => Unit = (_, _) => (),
      // Called with the harness the instant it exists, BEFORE any round is driven -- so a scenario
      // wiring a `HotStuffWatchdog` (whose `committeeNonEmpty`/`resetInMemoryState` closures need the
      // harness) can bind it without a forward-reference `var` that is still null when the watchdog's
      // first `check()` runs.
      onHarnessReady: DstHarness => Unit = _ => (),
      // Called once per leader-timeout tick iteration, AFTER that iteration's events have drained. A
      // watchdog must be checked INTERLEAVED with the ticks like this, not in a batch afterwards: its
      // whole contract is "has progress happened since my last check", so checking it only after all
      // ticking is done sees one undifferentiated progress-free stretch in BOTH arms and cannot
      // distinguish them (observed while restaging).
      afterTick: () => Unit = () => ()
  ): ArmResult = {
    var node0EnteredView = false
    var rejections       = 0
    val harness          = new DstHarness(
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
    onHarnessReady(harness)

    harness.leaderTurn(node = 0, view = 0, blockId = blockAt(9), blockHeight = PriorHeight)
    harness.run()
    withClue("setup: the cluster needs a genuine prior commit before anything diverges -- ") {
      harness.commits.count(_.height == PriorHeight) should be(4)
    }

    node0EnteredView = false
    harness.leaderTurn(node = 0, view = 1, blockId = blockAt(1), blockHeight = InitialHeight)
    var guard = 0
    while (!node0EnteredView && guard < 200) { harness.run(maxEvents = 1); guard += 1 }
    withClue(s"setup: node 0's own PREPARE QC must have formed (guard=$guard) -- ") {
      node0EnteredView should be(true)
    }
    withClue("setup: the branch must be in-flight -- QC'd, NOT yet committed -- before the divergence -- ") {
      harness.commits.count(_.height == InitialHeight) should be(0)
    }

    harness.advanceTipAndEpochAll(LaggedTip, committeeEpochOf)
    harness.setCommittedTipAll(blockAt(5), ReanchorHeight)
    withClue("setup: the stuck target must be genuinely stale under the new tips -- ") {
      (LaggedTip - InitialHeight) should be > Bound
    }
    withClue("setup: the re-anchor target must NOT be stale, or neither arm could ever commit again -- ") {
      (LaggedTip - ReanchorHeight) should be <= Bound
    }

    val commitsBefore = harness.commits.size
    harness.setRejectionCounter(() => rejections += 1)

    (1 to 60).foreach { _ =>
      harness.tickTimeoutAll()
      harness.tickTimeoutAll()
      harness.run(maxEvents = 200)
      afterTick()
    }

    ArmResult(
      newCommitHeights = harness.commits.drop(commitsBefore).map(_.height).distinct.sorted.toSeq,
      staleAbandoned = (0 until 4).map(harness.staleTargetsAbandoned).sum,
      staleSkipped = (0 until 4).map(harness.staleTargetSkippedProposals).sum,
      rejections = rejections,
      harness = harness
    )
  }

  "the F-6 self-sealing epoch trap, with the whole cluster's tips advanced past a stuck T2 target" should
    "RED: without the fix (maxTargetLag = Int.MaxValue), keep committing the STALE target and never re-anchor" in {
      val red = runArm(Seed, maxTargetLag = () => Int.MaxValue)

      withClue(s"RED evidence: newCommitHeights=${red.newCommitHeights} abandoned=${red.staleAbandoned} skipped=${red.staleSkipped} -- ") {
        // The trap: still chasing the stale in-flight branch, so the only thing the cluster can commit
        // is that branch, at InitialHeight. It never re-anchors to the fresh settled tip.
        red.newCommitHeights should be(Seq(InitialHeight))
        red.maxNewCommitHeight should be < ReanchorHeight
      }
      withClue("RED: with the fix neutralized, NO stale-target path may fire (it is a no-op by construction) -- ") {
        red.staleAbandoned should be(0)
        red.staleSkipped should be(0)
        red.reanchorFired should be(false)
      }
    }

  it should
    "GREEN: with the fix (a finite maxTargetLag), abandon the stale branch, re-anchor, and commit STRICTLY ABOVE the stale target" in {
      val green = runArm(Seed, maxTargetLag = () => Bound)
      val red   = runArm(Seed, maxTargetLag = () => Int.MaxValue) // same seed: identical jitter, only the bound differs

      // (a) The re-anchor produced real PROGRESS: a NEW commit strictly above what the stale branch
      //     could ever yield. This is the assertion the vacuous first version lacked -- it cannot be
      //     satisfied by any pre-divergence commit, and it goes RED the moment the bound is neutralized.
      withClue(s"GREEN evidence: newCommitHeights=${green.newCommitHeights} (RED's were ${red.newCommitHeights}) -- ") {
        green.maxNewCommitHeight should be > InitialHeight
        green.newCommitHeights should contain(ReanchorHeight)
        green.maxNewCommitHeight should be > red.maxNewCommitHeight
      }

      // (b) The stale-abandon path (the WARN + Kamon `hotstuff.stale-target-abandoned` site) genuinely
      //     FIRED -- via the coordinator's test-observable mirror of that counter, so the MECHANISM is
      //     proven to have run rather than inferred from the outcome.
      withClue(s"GREEN: the fix's re-anchor path must have fired -- abandoned=${green.staleAbandoned} skipped=${green.staleSkipped} -- ") {
        green.reanchorFired should be(true)
        green.staleAbandoned should be > 0
      }

      // (c) Rejections no worse than the RED arm: the fix stops a replica generating self-rejected QCs
      //     for a target it can never again accept.
      withClue(s"GREEN rejections=${green.rejections} vs RED rejections=${red.rejections} -- ") {
        green.rejections should be <= red.rejections
      }

      SafetyInvariants.checkAll(green.harness.commits.toSeq, green.harness.votes.toSeq) match {
        case Left(reason) => fail(s"safety violation with the F-6 fix active: $reason")
        case Right(())    => succeed
      }
    }

  it should "preserve noEquivocation across an ACTUAL re-anchor (no slashable double-vote)" in {
    val green = runArm(Seed, maxTargetLag = () => Bound)

    // Guard this companion against the same vacuity as the main arm: the invariant must be checked
    // across a re-anchor that DEMONSTRABLY happened, not merely one that was configured to be possible.
    withClue("noEquivocation must be checked across a REAL re-anchor -- ") {
      green.reanchorFired should be(true)
      green.maxNewCommitHeight should be > InitialHeight
    }

    // The harness records EVERY broadcast vote regardless of delivery (audit F-2 -- the double signature
    // is the violation, not its delivery); `SafetyInvariants.noEquivocation` runs the production
    // detector (`HotStuffSafety.equivocators`) directly over that stream.
    SafetyInvariants.noEquivocation(green.harness.votes.toSeq) match {
      case Left(reason) => fail(s"equivocation detected across the F-6 re-anchor: $reason")
      case Right(())    => succeed
    }
  }

  it should "let a wired watchdog reach commit-stall EXHAUSTION under the trap, and NOT reach it once the fix re-anchors" in {
    // The first version asserted only `isAutoRecoverySuspended == false` in the fixed arm -- trivially
    // true, since the RED arm never reached exhaustion either, so it compared nothing.
    //
    // Making the two arms genuinely distinguishable required understanding WHICH stall signal the trap
    // actually produces here, and the measurement is worth recording because the obvious choice is
    // wrong. Under this scenario node 0 emits ~730 `EnteredView` actions in BOTH arms -- the pacemaker
    // advances its view on every leader timeout regardless of whether anything is being agreed -- so a
    // VIEW-based progress signal (`recordProgress`) can never distinguish them, and the view-stall
    // counter never fires in either arm no matter how the thresholds are tuned. (Verified directly: an
    // earlier revision of this test wired exactly that and got RED=GREEN=0 recoveries.)
    //
    // What DOES differ is the COMMIT stream, which is exactly why `HotStuffWatchdog` carries a second,
    // independent `recordCommit()`/`commitStallThreshold` counter for the "views advancing while
    // commits stall" case (see its doc). Measured per arm:
    //   RED   -> last commit at height 50  (the STALE in-flight branch; never gets past it)
    //   GREEN -> last commit at height 92  (the re-anchored settled tip)
    // So "real progress" for this trap means committing ABOVE the stuck target, and that is what
    // `recordCommit` is gated on below. RED then genuinely commit-stalls and burns its recovery budget
    // to suspension; GREEN commits past the target and never exhausts.
    def wire(bound: Int, tag: String): (HotStuffWatchdog, ArmResult, Int) = {
      var harnessRef: Option[DstHarness] = None
      var recoveries                     = 0
      var escaped                        = false
      val watchdog                       = new HotStuffWatchdog(
        committeeNonEmpty = () => harnessRef.exists(_.currentCommittee().nonEmpty),
        lockPath = tempLockPath(tag),
        resetInMemoryState = () => { recoveries += 1; harnessRef.foreach(_.resetLocalSafetyState(0)) },
        stallThreshold = 1,
        // Small enough that five consecutive ineffective recoveries fit inside this scenario's 60 tick
        // iterations even with the watchdog's doubling backoff (1 + 2 + 4 + 8 + 16 = 31 < 60).
        commitStallThreshold = 1
      )
      val result = runArm(
        Seed,
        maxTargetLag = () => bound,
        onAction = (node, action) =>
          if (node == 0) action match {
            // A Rejected action must never count as progress -- exactly the masking risk
            // `HotStuffWatchdog`'s own doc comment flags ("e.g. one signed under a stale committee
            // epoch"), which IS this F-6 trap.
            case _: HotStuffAction.Rejected => ()
            // Only a commit STRICTLY ABOVE the stuck target counts as escaping the trap. Committing the
            // stale branch itself (RED, height 50) is precisely the trap succeeding, not progress.
            case HotStuffAction.Committed(_, h) =>
              watchdog.recordProgress()
              if (h > InitialHeight) { watchdog.recordCommit(); escaped = true }
            case _ => watchdog.recordProgress()
          },
        onHarnessReady = h => harnessRef = Some(h),
        // Check only while the replica has NOT yet escaped the trap. This is the honest framing of the
        // question the watchdog answers: "was the recovery budget exhausted BEFORE real progress
        // happened?" It is deliberately NOT "does the watchdog stall forever afterwards" -- in this
        // harness it must, because nothing keeps minting fresh heights after the single re-anchor
        // commit, so a post-escape stall is a property of the SIMULATION's finite work, not of the fix.
        afterTick = () => if (!escaped) watchdog.check()
      )
      (watchdog, result, recoveries)
    }

    // Both arms run the watchdog on the SAME schedule (one `check()` per tick iteration, via
    // `afterTick`), so the only difference between them is whether the F-6 fix is active.
    val (redWatchdog, redResult, redRecoveries)       = wire(Int.MaxValue, "watchdog-red")
    val (greenWatchdog, greenResult, greenRecoveries) = wire(Bound, "watchdog-green")

    withClue(
      s"RED recoveries=$redRecoveries suspended=${redWatchdog.isAutoRecoverySuspended} lastCommit=${redResult.maxNewCommitHeight} " +
        s"| GREEN recoveries=$greenRecoveries suspended=${greenWatchdog.isAutoRecoverySuspended} lastCommit=${greenResult.maxNewCommitHeight} -- "
    ) {
      // Under the trap the watchdog burns its whole budget and gives up: its reset cannot help, because
      // the epoch mismatch is derived from chain HEIGHT, not from anything `resetLocalSafetyState`
      // clears -- the audit's own point about why the watchdog is not the fix for F-6.
      redWatchdog.isAutoRecoverySuspended should be(true)
      // With the fix, node 0 re-anchors and commits past the stuck target, so the watchdog never
      // exhausts -- the fix REMOVES the trap rather than merely surviving it.
      greenWatchdog.isAutoRecoverySuspended should be(false)
      greenRecoveries should be < redRecoveries
    }
    withClue("the GREEN watchdog arm must have re-anchored for real -- ") {
      greenResult.reanchorFired should be(true)
      greenResult.maxNewCommitHeight should be > InitialHeight
      redResult.maxNewCommitHeight should be(InitialHeight)
    }
  }

  // NOTE ON SCOPE (relabelled honestly after review): this case does NOT exercise a re-anchor. It is the
  // M1 <-> F-6 INTERACTION check -- that a restarted replica's persisted `lastVotedView` bound and the
  // F-6 stale-target guard coexist without either wrongly blocking the other. The re-anchor itself is
  // proven by the GREEN arm above; the guard's effect on `lastVotedView` (review item IMPORTANT 2) is
  // proven directly in `HotStuffLagReanchorSpecification`. Kept because the combination is worth
  // pinning, but no longer described as re-anchor coverage.
  it should "M1 interaction check (NOT a re-anchor): a restarted replica's persisted lastVotedView bound and the F-6 guard coexist" in {
    val green                  = runArm(Seed, maxTargetLag = () => Bound)
    val preRestartHighestVoted = green.harness.votes.filter(_.node == 0).map(_.vote.view).maxOption.getOrElse(-1)

    val kps                     = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
    val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
      GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
    }

    var restartedVotesSent = Vector.empty[HotStuffVote]
    val restartedEffects   = new HotStuffEffects {
      def broadcast(m: Message): Unit = m match {
        case v: HotStuffVote => restartedVotesSent :+= v
        case _               => ()
      }
      def myVoterIndexes: Set[Int]                                   = Set(0)
      def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = if (idx == 0) Some(kps(0).sign(msg)) else None
      def onCommit(blockId: BlockId, height: Int): Unit              = ()
      def onEquivocation(proof: HotStuffEquivocationProof): Unit     = ()
    }
    // A fresh coordinator seeded from disk exactly as `Application.scala` does on a real boot.
    val restarted = new HotStuffCoordinator.Enabled(
      () => committee,
      restartedEffects,
      (_, _) => true,
      committeeEpochOf = committeeEpochOf,
      initialLastVotedView = preRestartHighestVoted,
      maxTargetLag = () => Bound,
      tipHeight = () => LaggedTip
    )

    // Target deliberately NON-stale (within Bound of the restarted tip), so this vote can only be
    // blocked by the M1 lastVotedView bound -- isolating it from the F-6 guard, which also runs.
    val freshTarget = ReanchorHeight
    restarted.onProposal(HotStuffProposal(preRestartHighestVoted, blockAt(77), None), freshTarget)
    withClue("M1: a proposal at or below the persisted high-water mark must not be voted -- ") {
      restartedVotesSent shouldBe empty
    }
    withClue("F-6 must NOT be what blocked it (the target is deliberately fresh) -- ") {
      restarted.staleTargetSkippedProposals should be(0)
    }

    restarted.onProposal(HotStuffProposal(preRestartHighestVoted + 1, blockAt(78), None), freshTarget)
    withClue("M1 must not be a permanent lockout: a strictly-higher view for a fresh target is votable -- ") {
      restartedVotesSent.map(_.view) should contain(preRestartHighestVoted + 1)
      restartedVotesSent.foreach(_.view should be > preRestartHighestVoted)
    }
  }
}
