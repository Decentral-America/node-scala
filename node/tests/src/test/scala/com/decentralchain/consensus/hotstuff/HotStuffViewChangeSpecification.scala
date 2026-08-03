package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsSignature, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

import java.nio.file.Files
import scala.collection.mutable

/** Task 8 Step 2 (see docs/hotstuff-step5-findings-and-rework.md §4 Option A): today
  * `HotStuffPacemaker.leaderFor`/`onTimeout` are pure, unit-tested primitives (see
  * `HotStuffPacemakerSpecification`) but are NOT actually wired as the shell's view-driver.
  * In production (`Application.scala`), "who proposes" is decided externally by checking the
  * FairPoS forger of a settled block height, and `HotStuffCoordinator.Enabled.onTimeout()` only
  * bumps the internal `EngineState.pacemaker` counter -- nothing observes that bump, so a leader
  * that goes silent (crash/partition) never triggers an actual view-change: no new leader ever
  * proposes as a result of the timeout alone.
  *
  * This spec demonstrates the gap and then (once implemented) proves the fix: a round-timer tick
  * that finds the view stalled (no QC formed since the last tick) must advance the pacemaker AND,
  * if the calling replica is the deterministically-rotated leader for the NEW view
  * (`HotStuffPacemaker.leaderFor`), automatically propose -- without any external FairPoS/height
  * check. This is a genuinely new coordinator entry point (`onRoundTimerTick`) and constructor
  * parameter (`blockSource`), so today this file does not compile -- that IS the RED: the wiring
  * this test requires does not exist yet.
  */
class HotStuffViewChangeSpecification extends FlatSpec {
  private val kps                     = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private val B: BlockId = ByteStr(Array.fill[Byte](32)(7))
  private val H          = 500

  private class RecordingEffects(self: Int) extends HotStuffEffects {
    val sent: mutable.ListBuffer[Message]                          = mutable.ListBuffer.empty
    def broadcast(m: Message): Unit                                = sent += m
    def myVoterIndexes: Set[Int]                                   = Set(self)
    def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = if (idx == self) Some(kps(self).sign(msg)) else None
    def onCommit(blockId: BlockId, height: Int): Unit              = ()
  }

  "onRoundTimerTick" should "leave the pacemaker view unchanged and propose nothing on the very first tick (no prior progress to compare against)" in {
    val fx   = new RecordingEffects(1)
    val node = new HotStuffCoordinator.Enabled(() => committee, fx, (_, _) => true, blockSource = () => Some((B, H)))
    node.currentView should be(0)
    node.onRoundTimerTick()
    node.currentView should be(0) // first tick just establishes the baseline; nothing was stalled yet
    fx.sent shouldBe empty
  }

  it should "advance the view and let the newly-rotated leader auto-propose when the round stalls (no QC progress between two ticks)" in {
    // node 1 is NOT leaderFor(view=0) (that's node 0), but per round-robin IS leaderFor(view=1).
    val fx1   = new RecordingEffects(1)
    val node1 = new HotStuffCoordinator.Enabled(() => committee, fx1, (_, _) => true, blockSource = () => Some((B, H)))

    node1.onRoundTimerTick() // baseline tick: view stays 0, node1 is not leader of view 0 -> no propose
    fx1.sent shouldBe empty

    node1.onRoundTimerTick() // no QC arrived between ticks => view 0 stalled => view-change to 1
    node1.currentView should be(1)
    HotStuffPacemaker.isLeader(1, node1.currentView, committee) should be(true)
    fx1.sent.collect { case p: HotStuffProposal => p } should not be empty
    fx1.sent.collect { case p: HotStuffProposal => p }.head.view should be(1)
    fx1.sent.collect { case p: HotStuffProposal => p }.head.blockId should be(B)
  }

  it should "NOT auto-propose on a stalled round when this replica is not the leader for the new view" in {
    // node 2 is leaderFor(view=2), not leaderFor(view=1) -- a stall from view 0 -> 1 must not make it propose.
    val fx2   = new RecordingEffects(2)
    val node2 = new HotStuffCoordinator.Enabled(() => committee, fx2, (_, _) => true, blockSource = () => Some((B, H)))

    node2.onRoundTimerTick() // baseline
    node2.onRoundTimerTick() // stall -> view-change to 1; node2 is not leaderFor(1)
    node2.currentView should be(1)
    HotStuffPacemaker.isLeader(2, node2.currentView, committee) should be(false)
    fx2.sent.collect { case p: HotStuffProposal => p } shouldBe empty
  }

  it should "NOT stall/advance the view if a QC actually formed between ticks (real progress, not a false timeout)" in {
    val fx0   = new RecordingEffects(0)
    val node0 = new HotStuffCoordinator.Enabled(() => committee, fx0, (_, _) => true, blockSource = () => Some((B, H)))

    node0.onRoundTimerTick() // baseline, view 0
    // Node 0 (leader of view 0) proposes and self-votes; feed the other 3 votes in to actually form a QC,
    // advancing the pacemaker to view 1 the "normal" way (via onQC), i.e. real progress happened.
    node0.onLeaderTurn(0, B, H)
    val proposal = fx0.sent.collect { case p: HotStuffProposal => p }.head
    (1 to 3).foreach { i =>
      val msg  = HotStuffQuorum.voteMessage(proposal.view, io.decentralchain.protobuf.block.HotStuffPhase.HOTSTUFF_PHASE_PREPARE, proposal.blockId, H)
      val vote = HotStuffVote(
        proposal.view,
        io.decentralchain.protobuf.block.HotStuffPhase.HOTSTUFF_PHASE_PREPARE,
        proposal.blockId,
        com.decentralchain.state.Height(H),
        i,
        kps(i).sign(msg).byteStr
      )
      node0.onVote(vote)
    }
    node0.currentView should be >= 1 // progressed via QC, not stall
    val viewAfterProgress = node0.currentView
    fx0.sent.clear()

    node0.onRoundTimerTick() // this call just re-baselines against the already-advanced view: no false stall
    node0.currentView should be(viewAfterProgress)
  }

  "the default blockSource (unset)" should "keep onRoundTimerTick a pure pacemaker bump with zero proposing side effects" in {
    // Backward-compat: existing call sites that don't pass blockSource must still compile and behave
    // exactly as a bare pacemaker advance (no auto-propose ever, since there's nothing to propose).
    val fx   = new RecordingEffects(1)
    val node = new HotStuffCoordinator.Enabled(() => committee, fx, (_, _) => true)
    node.onRoundTimerTick()
    node.onRoundTimerTick()
    node.currentView should be(1)
    fx.sent shouldBe empty
  }

  "HotStuffCoordinator.Disabled" should "report currentView 0 and ignore onRoundTimerTick (zero behaviour change when dcc.hotstuff.enabled=false)" in {
    HotStuffCoordinator.Disabled.currentView should be(0)
    noException should be thrownBy HotStuffCoordinator.Disabled.onRoundTimerTick()
    HotStuffCoordinator.Disabled.currentView should be(0)
  }

  // GREEN (was RED -- see git history on this file / commit 4bf3217e87 on this branch): proved that a
  // production-style HEIGHT-COUPLED `proposalValid` guard --
  //   (view, blockId) => blockchainUpdater.blockId(view).contains(blockId)
  // -- silently drops a legitimate pacemaker-driven view-change proposal, because it assumes
  // `view == the proposed block's height`, which a leader-timeout view-change necessarily breaks
  // (findings #2/#5's height/view conflation bug class, reintroduced at this guard). The fix: decouple
  // `proposalValid` from `view` entirely -- it now takes only `BlockId` and checks chain-membership
  // (does WE independently recognize this block as canonical, at whatever height it actually lives at),
  // which is view-number-agnostic by construction. View-ordering/lock safety is unaffected: it is still
  // enforced unconditionally by `HotStuffSafety.safeToVote` inside `HotStuffEngine.onProposal`, which
  // runs after this guard passes.
  "a pacemaker-driven view-change proposal that re-proposes the replica's own canonical block" should
    "be safe to vote for even though view != the block's real height, once proposalValid is chain-membership-based instead of height-coupled" in {
      // Mirrors production's ACTUAL (fixed) guard: "is blockId a block I recognize" -- no view/height
      // check at all. In this spec's fixed single-block world that's simply "is it B".
      val chainMembershipProposalValid: BlockId => Boolean = _ == B

      val fx1   = new RecordingEffects(1)
      val node1 =
        new HotStuffCoordinator.Enabled(() => committee, fx1, (_, _) => true, chainMembershipProposalValid, blockSource = () => Some((B, H)))

      node1.onRoundTimerTick() // baseline, view 0
      node1.onRoundTimerTick() // stall -> view-change to 1; node1 is leaderFor(1) -> auto-proposes (B, H)
      node1.currentView should be(1)

      fx1.sent.collect { case p: HotStuffProposal => p } should not be empty
      // B genuinely IS this replica's own canonical block (at its real, unchanged height H) and
      // re-proposing it on a view-change is exactly the standard HotStuff pacemaker liveness case --
      // it IS now votable, because the guard no longer conflates "view" with "height".
      fx1.sent.collect { case v: HotStuffVote => v } should not be empty
    }

  it should "still reject a proposal for a block the replica does NOT recognize, regardless of view (the guard's Byzantine-rejection purpose is preserved)" in {
    val chainMembershipProposalValid: BlockId => Boolean = _ == B
    val bogus: BlockId                                   = ByteStr(Array.fill[Byte](32)(66))

    val fx1   = new RecordingEffects(1)
    val node1 =
      new HotStuffCoordinator.Enabled(() => committee, fx1, (_, _) => true, chainMembershipProposalValid, blockSource = () => Some((bogus, H)))

    node1.onRoundTimerTick() // baseline, view 0
    node1.onRoundTimerTick() // stall -> view-change to 1; node1 auto-proposes the bogus block

    fx1.sent.collect { case p: HotStuffProposal => p } should not be empty // broadcast is unconditional
    fx1.sent.collect { case v: HotStuffVote => v } shouldBe empty // but self-vote is rejected: not chain-recognized
  }

  // Regression test for the post-restart `lockedQC = None` bootstrap-window narrowing documented at
  // `HotStuffSafety.safeToVote`'s `None` branch: a freshly-restarted replica (a fresh `SafetyState()`,
  // exactly as `HotStuffCoordinator.Enabled` constructs on process start -- no persisted lock) admits
  // ANY view-ordering-valid proposal, including a Byzantine leader's replay of an old-but-real on-chain
  // block under an artificially inflated view number. This is a genuine narrowing versus the old
  // view-coupled guard, but it is bounded: `HotStuffEngine.onQC`'s `qc.blockHeight > committedHeight`
  // check is monotonic in height regardless of view, so no vote/QC this window admits can ever regress
  // (or falsely fast-forward past what a real quorum has actually certified) the finalized height.
  //
  // Uses a NON-trivial `extendsBranch` (models real chain ancestry via block height) rather than this
  // spec's usual permissive `(_, _) => true`, per the reviewer's note that the all-permissive stub
  // leaves this exact scenario untested.
  private val Bold: BlockId   = ByteStr(Array.fill[Byte](32)(8))
  private val Hold            = 300
  private val Bolder: BlockId = ByteStr(Array.fill[Byte](32)(9))
  private val Holder          = 100

  private val heights: Map[BlockId, Int]                       = Map(B -> H, Bold -> Hold)
  private val extendsBranchReal: (BlockId, BlockId) => Boolean = (child, ancestor) =>
    child == ancestor || (for {
      hc <- heights.get(child)
      ha <- heights.get(ancestor)
    } yield hc >= ha).getOrElse(false)

  private def committeeVoteFor(view: Int, phase: HotStuffPhase, blockId: BlockId, height: Int, voterIndex: Int): HotStuffVote = {
    val msg = HotStuffQuorum.voteMessage(view, phase, blockId, height)
    HotStuffVote(view, phase, blockId, com.decentralchain.state.Height(height), voterIndex, kps(voterIndex).sign(msg).byteStr)
  }

  "HotStuffSafety.safeToVote's lockedQC=None branch (fresh post-restart SafetyState)" should
    "admit a Byzantine leader's replay of an old-but-real block under an inflated view, unlike a replica that retained its pre-restart lock" in {
      val inflatedView     = 9999
      val replayedProposal = HotStuffProposal(inflatedView, Bold, justify = None)
      val freshlyRestarted = SafetyState() // lockedQC = None, lastVotedView = -1: exactly post-restart

      // The narrowing itself: nothing locked yet -> admitted, subject only to view > lastVotedView.
      HotStuffSafety.safeToVote(replayedProposal, freshlyRestarted, extendsBranchReal) should be(true)

      // Contrast: had this replica retained a PRE-restart lock on B (a real PRE_COMMIT QC), the exact
      // same replayed proposal for the older, non-descendant block Bold is correctly rejected --
      // proving the gap is specific to the `lockedQC = None` bootstrap window, not a general hole in
      // `safeToVote`.
      val lockVotesForB    = (0 to 2).map(i => committeeVoteFor(5, HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT, B, H, i))
      val lockQCForB       = HotStuffQuorum.formQC(lockVotesForB, committee).toOption.get
      val preRestartLocked = SafetyState(lockedQC = Some(lockQCForB))

      extendsBranchReal(Bold, B) should be(false)                                // Bold does not extend the locked branch
      replayedProposal.justify.exists(_.view > lockQCForB.view) should be(false) // no newer justify-QC either
      HotStuffSafety.safeToVote(replayedProposal, preRestartLocked, extendsBranchReal) should be(false)
    }

  "HotStuffEngine.onQC's monotonic commit-height guard" should
    "bound the lockedQC=None narrowing: a freshly-restarted replica's committedHeight can advance to a replayed-but-real block's height, but can never regress once a later, equally-real, lower-height QC arrives" in {
      val inflatedView = 9999

      // A REAL quorum (3 of 4 committee members genuinely BLS-sign) forms a cryptographically valid
      // COMMIT QC for the replayed old block Bold, under the inflated view -- exactly what the
      // `lockedQC = None` window (proved above) would let a freshly-restarted replica help produce.
      val replayCommitVotes = (0 to 2).map(i => committeeVoteFor(inflatedView, HotStuffPhase.HOTSTUFF_PHASE_COMMIT, Bold, Hold, i))
      val replayCommitQC    = HotStuffQuorum.formQC(replayCommitVotes, committee).toOption.get

      val freshEngine                  = EngineState(committee) // committedHeight = 0: matches a freshly-restarted replica
      val (afterReplay, actionsReplay) = HotStuffEngine.onQC(freshEngine, replayCommitQC)

      // The commit DOES form (Bold is a real block, and the QC is cryptographically genuine) -- this is
      // the "wastes a round" consequence the finding accepts, not a fabricated commit.
      actionsReplay should contain(HotStuffAction.Committed(Bold, Hold))
      afterReplay.committedHeight should be(Hold)

      // Now a second, ALSO cryptographically real, COMMIT QC for a LOWER height arrives (e.g. a stale
      // round's message delivered late). The monotonic guard (`qc.blockHeight > committedHeight`) must
      // reject it from committing, regardless of how it got formed.
      val staleVotes = (0 to 2).map(i => committeeVoteFor(inflatedView + 1, HotStuffPhase.HOTSTUFF_PHASE_COMMIT, Bolder, Holder, i))
      val staleQC    = HotStuffQuorum.formQC(staleVotes, committee).toOption.get
      assert(Holder < Hold) // sanity: the "stale" QC really is for a lower height than what's committed

      val (afterStale, actionsStale) = HotStuffEngine.onQC(afterReplay, staleQC)
      actionsStale.collect { case c: HotStuffAction.Committed => c } shouldBe empty // guard blocks the regression
      afterStale.committedHeight should be(Hold) // NOT regressed to Holder
    }

  // RED (closing the gap documented above and at HotStuffSafety.safeToVote's `lockedQC=None` branch):
  // today `HotStuffCoordinator.Enabled` has no way to seed its `SafetyState` with a pre-existing
  // `lockedQC` at construction time, nor any hook to observe when the lock advances so it could be
  // persisted -- a real coordinator restart is therefore *exactly* `SafetyState()`, unconditionally.
  // This test constructs a "pre-restart" replica, lets it genuinely lock onto B via a real quorum,
  // captures that lock through a not-yet-existing `onLockedQCPersist` hook, and feeds it back into a
  // "post-restart" replica via a not-yet-existing `initialLockedQC` constructor parameter. Neither
  // parameter exists yet, so this file does not compile -- that IS the RED, same pattern as the
  // `onRoundTimerTick`/`blockSource` RED earlier in this file.
  "a coordinator restart that reloads its persisted lockedQC" should
    "reject the same old-but-real block replay under an inflated view that a blank-slate restart would wrongly admit" in {
      val fx1                                  = new RecordingEffects(0)
      var persisted: Option[QuorumCertificate] = None
      val node1                                = new HotStuffCoordinator.Enabled(
        () => committee,
        fx1,
        extendsBranchReal,
        onLockedQCPersist = qc => persisted = Some(qc)
      )

      // Node 1 genuinely locks onto B: feed a real PRE_COMMIT QC (quorum of real BLS votes) via onQC.
      val lockVotesForB = (0 to 2).map(i => committeeVoteFor(5, HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT, B, H, i))
      val lockQCForB    = HotStuffQuorum.formQC(lockVotesForB, committee).toOption.get
      node1.onQC(lockQCForB)

      persisted should be(Some(lockQCForB)) // the hook fired with exactly the newly-locked QC

      // "Restart": a fresh coordinator, but seeded with the persisted lock instead of a blank SafetyState.
      val fx2       = new RecordingEffects(0)
      val restarted = new HotStuffCoordinator.Enabled(
        () => committee,
        fx2,
        extendsBranchReal,
        initialLockedQC = persisted
      )

      val inflatedView     = 9999
      val replayedProposal = HotStuffProposal(inflatedView, Bold, justify = None)
      restarted.onProposal(replayedProposal, Hold)

      fx2.sent.collect { case v: HotStuffVote => v } shouldBe empty // rejected: locked on B, Bold doesn't extend it

      // Contrast: a blank-slate restart (today's only behaviour) DOES vote for the same replay -- proving
      // this is a real fix, not a coincidence of the test setup.
      val fx3          = new RecordingEffects(0)
      val blankRestart = new HotStuffCoordinator.Enabled(() => committee, fx3, extendsBranchReal)
      blankRestart.onProposal(replayedProposal, Hold)
      fx3.sent.collect { case v: HotStuffVote => v } should not be empty
    }

  // Adversarial-review Condition 1 (reviewer A): the corrupted-file test coverage in
  // `HotStuffLockedQCStoreSpecification` only exercises outright-invalid-wire-format bytes
  // (`Array[Byte](1,2,3,4,5)`) -- a strawman, since that can never parse into a `QuorumCertificate` at
  // all. The genuinely adversarial case is a WELL-FORMED, PARSEABLE protobuf QC with fabricated content
  // (garbage blockId, an inflated view number, a signature that would never pass real BLS verification)
  // -- exactly what a corrupted-but-still-valid-shape on-disk file, or a compromised/buggy write path,
  // could hand back from `HotStuffLockedQCStore.load`. This proves the fabrication's blast radius is
  // bounded to a LIVENESS/self-DoS effect on the one replica that loaded it, never a safety violation:
  //  (a) it round-trips through the real store faithfully (the store has no way to know it's fabricated
  //      -- it only checks the bytes parse, not that the QC is real);
  //  (b) seeded as `initialLockedQC`, it makes THIS replica's own `safeToVote` permanently reject a
  //      genuine proposal that doesn't extend the fabricated (unknown) blockId and isn't justified above
  //      the fabricated inflated view -- a self-inflicted liveness stall, not a wrongly-cast vote;
  //  (c) the OTHER honest committee members don't need this replica's vote at all: their stake alone
  //      already clears the 2/3 quorum, so the network commits normally around the self-DoS'd replica;
  //  (d) even if the very same fabricated QC were broadcast onto the wire, `HotStuffEngine.onQC`'s
  //      independent BLS/quorum re-verification (which never consults `lockedQC`) rejects it outright --
  //      it can never be "wrongly accepted" as a real branch/commit.
  "a fabricated-but-well-formed QC loaded as initialLockedQC" should
    "never let this replica cast an unsafe vote or corrupt consensus -- only ever a bounded, self-contained liveness stall on that one replica" in {
      val garbageBlockId: BlockId = ByteStr(Array.fill[Byte](32)(200.toByte)) // fabricated: not a real block on any chain
      val inflatedView            = Int.MaxValue - 1
      val garbageSignature        = ByteStr(Array.fill[Byte](96)(42)) // right shape for a BLS agg sig, but not real

      val fabricatedQC = QuorumCertificate(
        view = inflatedView,
        phase = HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT,
        blockId = garbageBlockId,
        blockHeight = com.decentralchain.state.Height(1),
        signerIndexes = Seq(0, 1, 2),
        aggregatedSignature = garbageSignature
      )

      // (a) It is exactly the shape `HotStuffLockedQCStore.load` would hand back from a corrupted-but-
      // parseable persisted file: round-trip it through the real store to prove this scenario is
      // realistic, not synthetic -- the store faithfully persists/reloads fabricated content because it
      // only checks that the bytes parse as a QC, never that the QC is cryptographically real.
      val storeDir  = Files.createTempDirectory("hotstuff-fabricated-qc-spec")
      val storePath = storeDir.resolve("locked-qc.dat")
      HotStuffLockedQCStore.save(storePath, fabricatedQC)
      val reloaded = HotStuffLockedQCStore.load(storePath)
      reloaded should be(Some(fabricatedQC))

      // Node 3 boots "post-restart" seeded with this fabricated lock, exactly as Application.scala's
      // wiring would seed `initialLockedQC` from whatever `HotStuffLockedQCStore.load` returns.
      val fxPoisoned = new RecordingEffects(3)
      val poisoned   = new HotStuffCoordinator.Enabled(() => committee, fxPoisoned, extendsBranchReal, initialLockedQC = reloaded)

      // (b) RESTRICTIVE consequence: a genuine, honestly-proposed block (B, real height H) doesn't
      // extend the fabricated lock's (unknown, off-chain) blockId, and carries no justify-QC above the
      // fabricated inflated view -- `safeToVote`'s `Some(locked)` branch permanently rejects it. This
      // replica can no longer vote for anything short of a QC justified above `Int.MaxValue - 1`: a
      // self-inflicted liveness stall, never an unsafe vote.
      val genuineProposal = HotStuffProposal(view = 10, blockId = B, justify = None)
      poisoned.onProposal(genuineProposal, H)
      fxPoisoned.sent.collect { case v: HotStuffVote => v } shouldBe empty // self-DoS: this node casts nothing

      // (c) The fabrication does not escalate into a network-wide problem: the other 3 committee members
      // form a real quorum for B WITHOUT node 3's participation at all -- their combined stake (75 of
      // 100) alone already clears the 2/3-stake threshold.
      val honestVotes = (0 to 2).map(i => committeeVoteFor(10, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, B, H, i))
      val honestQC     = HotStuffQuorum.formQC(honestVotes, committee)
      honestQC.isRight should be(true) // quorum reached with zero help from the poisoned replica

      // (d) And if the fabricated QC itself were somehow broadcast onto the wire, the engine's own
      // independent verification -- which never consults `lockedQC` -- rejects it outright: fabricated
      // content can never be "wrongly accepted" as a real branch or commit, on this node or any other.
      val (_, actions) = HotStuffEngine.onQC(EngineState(committee), fabricatedQC)
      actions should matchPattern { case Seq(HotStuffAction.Rejected(_)) => }
    }
}
