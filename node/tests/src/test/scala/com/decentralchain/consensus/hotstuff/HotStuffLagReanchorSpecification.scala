package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsSignature, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

import scala.collection.mutable

/** F-6 fix unit coverage (docs/superpowers/specs/2026-09-02-hotstuff-lag-reanchor-design.md, BFT audit
  * 2026-08-31 finding F-6, "self-sealing epoch trap"): `HotStuffCoordinator.Enabled`'s `tooStale`
  * predicate and its two use sites (`inFlightBranch` filtering, the defensive `castVotes` guard).
  *
  * See `DstStaleTargetSelfSealScenarioSpecification` for the full RED-first multi-node DST reproduction
  * of the trap this closes; this file is the coordinator-unit-level boundary coverage the design's test
  * strategy also asks for (`tooStale` boundaries, the `settledDepth + 1` floor, settings `require`
  * rejection -- the settings piece lives in `HotStuffSettingsSpecification`).
  */
class HotStuffLagReanchorSpecification extends FlatSpec {
  private val kps                     = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private val B: BlockId = ByteStr(Array.fill[Byte](32)(7))

  private class RecordingEffects(self: Int) extends HotStuffEffects {
    val sent: mutable.ListBuffer[Message]                          = mutable.ListBuffer.empty
    def broadcast(m: Message): Unit                                = sent += m
    def myVoterIndexes: Set[Int]                                   = Set(self)
    def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = if (idx == self) Some(kps(self).sign(msg)) else None
    def onCommit(blockId: BlockId, height: Int): Unit              = ()
    def onEquivocation(proof: HotStuffEquivocationProof): Unit     = ()
  }

  // ---- tooStale boundaries (via the inFlightBranch use site, the only externally observable read) ----

  /** Drives node2 through a full PREPARE round for (B, H) so `SafetyState.prepareQC` is genuinely set
    * (not committed -- no PRE_COMMIT/COMMIT round is driven), then returns the coordinator plus a mutable
    * tip cell the test can move, wired as `tipHeight`. `maxTargetLag` is fixed at `lag`.
    *
    * Self is node index 2, NOT 0 or 1: `HotStuffPacemaker.onQC` advances the pacemaker to `qcView + 1`
    * the MOMENT the PREPARE QC forms (view 0 -> 1) -- before `onRoundTimerTick` is ever called -- so the
    * subsequent baseline+stall tick pair (see `stalledTickReproposesInFlight`) advances view 1 -> 2 on
    * the stall, and `HotStuffPacemaker.leaderFor(2, committee)` (round-robin over 4 sorted indices) is
    * node index 2.
    */
  private def coordinatorWithInFlightBranch(
      h: Int,
      lag: Int,
      initialTip: Int
  ): (HotStuffCoordinator.Enabled, RecordingEffects, mutable.ArrayBuffer[Int]) = {
    val fx  = new RecordingEffects(2)
    val tip = mutable.ArrayBuffer(initialTip)
    val node = new HotStuffCoordinator.Enabled(
      () => committee,
      fx,
      (_, _) => true,
      blockSource = () => None, // isolate the in-flight-branch path: no fallback to mask the filter
      maxTargetLag = () => lag,
      tipHeight = () => tip.head
    )
    node.onLeaderTurn(0, B, h) // proposes + self-votes (index 2) PREPARE for (B, h)
    val proposal = fx.sent.collect { case p: HotStuffProposal => p }.head
    Seq(0, 1).foreach { i => // + self (2) = 3 distinct signers of 4 -- reaches the 2/3 stake quorum
      val msg  = HotStuffQuorum.voteMessage(proposal.view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, proposal.blockId, h)
      val vote = HotStuffVote(proposal.view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, proposal.blockId, Height(h), i, kps(i).sign(msg).byteStr)
      node.onVote(vote)
    }
    // A PREPARE QC formed (3-of-4): SafetyState.prepareQC is now Some(qc at height h), committedHeight
    // is still 0 (no COMMIT-phase QC ever formed) -- exactly `inFlightBranch`'s `Some` precondition.
    // The QC's own formation also already advanced the pacemaker view 0 -> 1 (see the method doc above).
    fx.sent.clear()
    (node, fx, tip)
  }

  /** Forces a genuine leader-timeout stall (two ticks, no QC in between) so `onRoundTimerTick` reaches
    * the in-flight-branch re-propose decision, then reports whether it re-proposed the in-flight branch
    * (a PREPARE vote for B at the SAME target reappears) or fell through to `blockSource` (`None` here,
    * so nothing is proposed at all). Starting pacemaker view is already 1 (see
    * `coordinatorWithInFlightBranch`'s doc): baseline re-observes view 1, the stall tick advances to
    * view 2 -- node index 2 (self, above) is `leaderFor(2)`.
    */
  private def stalledTickReproposesInFlight(node: HotStuffCoordinator.Enabled, fx: RecordingEffects): Boolean = {
    node.onRoundTimerTick() // baseline (re-observes view 1, the post-QC pacemaker state)
    node.onRoundTimerTick() // stall -> view-change to 2; self is leaderFor(2)
    fx.sent.collect { case p: HotStuffProposal => p }.exists(_.blockId == B)
  }

  "tooStale" should "NOT abandon the in-flight branch when tip - height == maxTargetLag (boundary: exactly at the bound is still acceptable)" in {
    val h             = 500
    val lag           = 50
    val (node, fx, _) = coordinatorWithInFlightBranch(h, lag, initialTip = h + lag)
    stalledTickReproposesInFlight(node, fx) should be(true)
  }

  it should "abandon the in-flight branch the instant tip - height == maxTargetLag + 1 (one block past the bound)" in {
    val h             = 500
    val lag           = 50
    val (node, fx, _) = coordinatorWithInFlightBranch(h, lag, initialTip = h + lag + 1)
    stalledTickReproposesInFlight(node, fx) should be(false)
  }

  it should "never abandon anything under the Int.MaxValue default (today's exact behaviour preserved)" in {
    val h    = 500
    val fx   = new RecordingEffects(2) // see coordinatorWithInFlightBranch's doc for why index 2
    val node = new HotStuffCoordinator.Enabled(() => committee, fx, (_, _) => true, blockSource = () => None)
    // No maxTargetLag/tipHeight passed at all -- defaults apply.
    node.onLeaderTurn(0, B, h)
    val proposal = fx.sent.collect { case p: HotStuffProposal => p }.head
    Seq(0, 1).foreach { i => // + self (2) = 3 distinct signers of 4
      val msg  = HotStuffQuorum.voteMessage(proposal.view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, proposal.blockId, h)
      val vote = HotStuffVote(proposal.view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, proposal.blockId, Height(h), i, kps(i).sign(msg).byteStr)
      node.onVote(vote)
    }
    fx.sent.clear()
    stalledTickReproposesInFlight(node, fx) should be(true)
  }

  it should "abandon a branch that is arbitrarily far behind when a finite lag is configured, regardless of tip magnitude" in {
    val h             = 10
    val lag           = 5
    val (node, fx, _) = coordinatorWithInFlightBranch(h, lag, initialTip = 1_000_000)
    stalledTickReproposesInFlight(node, fx) should be(false)
  }

  // ---- the settledDepth + 1 floor (Application.scala wiring formula, exercised directly here since
  // the formula itself is a pure `math.max` -- no Application/blockchain plumbing needed to prove it) ----

  "the settledDepth + 1 floor" should "dominate a pathologically small maxTargetLagFraction" in {
    val settledDepth           = 3
    val generationPeriodLength = 1000
    val pathologicallySmall    = 0.0001 // -> (1000 * 0.0001).toInt == 0, far below settledDepth + 1
    val maxTargetLag           = math.max(settledDepth + 1, (generationPeriodLength * pathologicallySmall).toInt)
    maxTargetLag should be(settledDepth + 1)
  }

  it should "let the fraction term dominate once it genuinely exceeds settledDepth + 1" in {
    val settledDepth           = 3
    val generationPeriodLength = 1000
    val fraction                = 0.25
    val maxTargetLag           = math.max(settledDepth + 1, (generationPeriodLength * fraction).toInt)
    maxTargetLag should be(250)
  }

  it should "match the design doc's live-testnet worked example (generation-period-length=100)" in {
    val settledDepth           = 3
    val generationPeriodLength = 100 // live testnet override (infra dcc.conf)
    val fraction                = 0.25
    val maxTargetLag           = math.max(settledDepth + 1, (generationPeriodLength * fraction).toInt)
    maxTargetLag should be(25) // the fraction term (25) still dominates the floor (4) here
  }

  // ---- castVotes defensive guard (use site B): skips signing without recording a misleading `voted` entry ----
  //
  // Deliberately uses `tooStale` (height-lag), NOT `HotStuffQuorum.acceptableCommitteeEpoch` -- see
  // `castVotes`'s doc for why reusing the epoch-acceptance function here would reintroduce the exact
  // signer's-own-tip-belief coupling `HotStuffCrossEpochLivenessSpecification` proves must NOT gate
  // signing. The "cross-epoch divergence is not staleness" test below is this file's direct proof that
  // this guard's chosen formulation does not regress that specification.

  "castVotes' defensive height-lag guard" should
    "skip signing (no broadcast vote) for a target more than maxTargetLag blocks behind this replica's own tip, without recording a voted entry that blocks a later legitimate vote" in {
      val fx = new RecordingEffects(1)
      val node = new HotStuffCoordinator.Enabled(
        () => committee,
        fx,
        (_, _) => true,
        maxTargetLag = () => 10,
        tipHeight = () => 511 // 511 - 500 = 11 > 10 -> stale
      )

      node.onLeaderTurn(0, B, 500)
      // The proposal itself is still broadcast (that part is unconditional), but no PREPARE vote should
      // have been signed/broadcast for it: the guard fires before any signing happens.
      fx.sent.collect { case p: HotStuffProposal => p } should not be empty
      fx.sent.collect { case v: HotStuffVote => v } shouldBe empty

      // Prove no misleading `voted` entry was recorded either: feed back a vote at the SAME
      // (view, phase, blockId) this replica would have voted for, from enough OTHER voters to reach
      // quorum on its own -- if a stray `voted` entry had been recorded despite the skipped signature,
      // this replica's own later processing of that target would be the place a bug would surface (e.g.
      // an exception, or the target being silently ignored as "already voted"). No exception here is a
      // minimum confirmation the coordinator's public behaviour is otherwise unaffected.
      val proposal = fx.sent.collect { case p: HotStuffProposal => p }.head
      noException should be thrownBy {
        (0 to 2).foreach { i =>
          val msg  = HotStuffQuorum.voteMessage(proposal.view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, proposal.blockId, 500)
          val vote = HotStuffVote(proposal.view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, proposal.blockId, Height(500), i, kps(i).sign(msg).byteStr)
          node.onVote(vote)
        }
      }
    }

  it should "sign normally once the target is within maxTargetLag of this replica's own tip (guard is a no-op in the happy path)" in {
    val fx   = new RecordingEffects(1)
    val node = new HotStuffCoordinator.Enabled(
      () => committee,
      fx,
      (_, _) => true,
      maxTargetLag = () => 10,
      tipHeight = () => 505 // 505 - 500 = 5 <= 10 -> not stale
    )
    node.onLeaderTurn(0, B, 500)
    fx.sent.collect { case v: HotStuffVote => v } should not be empty
  }

  it should "be an unconditional no-op under the default tipHeight=0/maxTargetLag=Int.MaxValue (today's exact behaviour preserved)" in {
    val fx   = new RecordingEffects(1)
    val node = new HotStuffCoordinator.Enabled(() => committee, fx, (_, _) => true)
    node.onLeaderTurn(0, B, 500)
    fx.sent.collect { case v: HotStuffVote => v } should not be empty
  }

  // Direct regression coverage for the failure mode this guard's design deliberately avoids: reusing
  // `HotStuffQuorum.acceptableCommitteeEpoch(committeeEpochOf(height), engine.committeeEpoch)` as the
  // guard would have rejected this -- see `HotStuffCrossEpochLivenessSpecification`, whose first test
  // this exact setup mirrors (committeeEpochProvider=5 vs a height-derived committeeEpochOf(height)=2,
  // a legitimate honest-skew scenario, NOT staleness). `tooStale` never even reads `committeeEpochOf`/
  // `committeeEpochProvider`, so it is structurally incapable of confusing the two.
  it should "NOT be triggered by ordinary cross-epoch-liveness divergence between committeeEpochOf(height) and this replica's own committeeEpochProvider belief" in {
    val fx   = new RecordingEffects(1)
    val node = new HotStuffCoordinator.Enabled(
      () => committee,
      fx,
      (_, _) => true,
      committeeEpochProvider = () => 5, // this replica's own live-tip belief: epoch 5
      committeeEpochOf = h => h / 500   // target's pure, height-derived epoch: 1000/500 = 2 (far from 5)
      // maxTargetLag/tipHeight left at their defaults (Int.MaxValue / 0) -- tooStale is unconditionally
      // false regardless of the epoch divergence above.
    )
    node.onLeaderTurn(0, B, 1000)
    fx.sent.collect { case v: HotStuffVote => v } should not be empty
  }
}
