package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsSignature, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

/** Audit finding F-2 (HIGH) regression coverage: `resetLocalSafetyState()` used to blank the WHOLE
  * `SafetyState` via the no-arg constructor, which also reset `lastVotedView` to `-1`. That field is
  * the ONLY thing in `HotStuffSafety.safeToVote` preventing this replica from voting twice in the same
  * view for two DIFFERENT blocks (the `voted` set is keyed `(view, phase, blockId)`, so it only blocks
  * re-voting the SAME target, never a conflicting one).
  *
  * The exploit the audit traced: the watchdog fires while this replica's own PREPARE votes for view `v`
  * are genuinely in flight (no QC back yet, so `recordProgress()` never fired). The reset drops
  * `lastVotedView` to `-1`; a proposal for a different block, still at view `v`, then satisfies
  * `v > -1` and -- with `lockedQC` also cleared -- is voted for. The replica has now signed two
  * conflicting PREPARE votes at the same `(view, phase)`: exactly the condition
  * `HotStuffSafety.equivocators` exists to detect, produced by an honest node's own recovery path.
  *
  * The fix preserves `lastVotedView` across the reset while still clearing `lockedQC`/`prepareQC` --
  * which is all the manual `rm locked-qc.dat` + restart procedure this automates actually reproduces
  * (`lastVotedView` is not persisted to disk at all, so it has no manual-procedure equivalent).
  */
class HotStuffResetDoubleVoteSpecification extends FlatSpec {
  private val kps                     = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }

  private def blockId(b: Byte): BlockId = ByteStr(Array.fill[Byte](32)(b))

  /** Coordinator that records every vote it broadcasts, so the test can assert on the actual signed
    * wire messages (not merely on internal state) and run them through `HotStuffSafety.equivocators`.
    *
    * @param multiKey when true this single coordinator holds (and will sign with) EVERY committee key,
    *                 purely so a test can drive a real quorum -- and therefore a real `lockedQC` -- without
    *                 standing up a whole cluster. Mirrors the same device used by
    *                 `HotStuffWatchdogSpecification`'s behavioural lock-clearing test.
    */
  private def newCoordinator(
      multiKey: Boolean = false,
      initialLastVotedView: Int = -1
  ): (HotStuffCoordinator.Enabled, scala.collection.mutable.ListBuffer[HotStuffVote]) = {
    val cast = scala.collection.mutable.ListBuffer.empty[HotStuffVote]
    val fx   = new HotStuffEffects {
      def broadcast(m: Message): Unit = m match {
        case v: HotStuffVote => cast += v
        case _               => ()
      }
      def myVoterIndexes: Set[Int]                                   = if (multiKey) Set(0, 1, 2) else Set(0)
      def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] =
        if (multiKey) Some(kps(idx).sign(msg)) else Option.when(idx == 0)(kps(0).sign(msg))
      def onCommit(blockId: BlockId, height: Int): Unit = ()
      def onEquivocation(proof: HotStuffEquivocationProof): Unit = ()
    }
    val c = new HotStuffCoordinator.Enabled(
      committeeProvider = () => committee,
      effects = fx,
      // No block extends any other: isolates the `lastVotedView` guard from the lock/liveness branch,
      // so a pass/fail here is unambiguously about the view bound this finding is about. It also makes
      // a genuine `lockedQC` strictly blocking for any other block -- which is what lets the
      // lock-clearing test below actually bite (see its comment).
      extendsBranch = (_, _) => false,
      initialLastVotedView = initialLastVotedView
    )
    (c, cast)
  }

  private def voteFor(view: Int, phase: HotStuffPhase, blockId: BlockId, height: Int, idx: Int) =
    HotStuffVote(
      view,
      phase,
      blockId,
      Height(height),
      idx,
      kps(idx).sign(HotStuffQuorum.voteMessage(view, phase, blockId, height)).byteStr
    )

  /** Drive `coordinator` to a REAL `lockedQC` on `blockId` at `view`: propose it (the coordinator
    * self-votes PREPARE), then feed the other committee members' PREPARE votes so a PREPARE QC forms,
    * then their PRE_COMMIT votes so a PRE_COMMIT QC forms -- and only a PRE_COMMIT QC ever sets
    * `lockedQC` (`HotStuffSafety.update`). Without this, `lockedQC` stays `None` and "the reset cleared
    * the lock" is indistinguishable from the reset doing nothing at all.
    */
  private def establishLock(coordinator: HotStuffCoordinator.Enabled, view: Int, blockId: BlockId, height: Int): Unit = {
    coordinator.onProposal(HotStuffProposal(view, blockId, None), height)
    (1 to 2).foreach(i => coordinator.onVote(voteFor(view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockId, height, i)))
    (1 to 2).foreach(i => coordinator.onVote(voteFor(view, HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT, blockId, height, i)))
  }

  "resetLocalSafetyState (audit F-2)" should
    "NOT admit a conflicting proposal at the SAME view after a mid-flight watchdog reset" in {
      val (coordinator, cast) = newCoordinator()
      val blockA              = blockId(7)
      val blockB              = blockId(9) // different, conflicting block -- same view

      // 1. Replica votes PREPARE for block A at view 5. Its votes are now in flight; no QC comes back.
      coordinator.onProposal(HotStuffProposal(5, blockA, None), 100)
      cast.map(_.blockId).toList should contain(blockA)

      // 2. The watchdog fires WHILE those votes are still in flight (this is the whole point of the
      //    finding: no QC formed, so nothing ever called `recordProgress()`).
      coordinator.resetLocalSafetyState()

      // 3. A conflicting proposal for block B arrives, still at view 5. Pre-fix, `lastVotedView` was
      //    now -1, so `5 > -1` admitted it and the replica double-signed view 5.
      cast.clear()
      coordinator.onProposal(HotStuffProposal(5, blockB, None), 100)

      cast.map(_.blockId).toList should not contain blockB
    }

  it should "leave no equivocation signature on the wire across the whole reset sequence" in {
    val (coordinator, cast) = newCoordinator()
    val blockA              = blockId(7)
    val blockB              = blockId(9)

    coordinator.onProposal(HotStuffProposal(5, blockA, None), 100)
    coordinator.resetLocalSafetyState()
    coordinator.onProposal(HotStuffProposal(5, blockB, None), 100)
    // Also try the same view via a DIFFERENT height -- audit path (b), which needs no reorg: a real,
    // chain-resident block at another height re-proposed under the same view number.
    coordinator.onProposal(HotStuffProposal(5, blockB, None), 101)

    HotStuffSafety.equivocators(cast.toList) should be(empty)
  }

  it should "still clear lockedQC/prepareQC so the watchdog's actual recovery purpose survives" in {
    // MUTATION-TESTED (post-review): this test previously drove proposals with `justify = None`, so
    // `lockedQC` was never populated (only `HotStuffSafety.update` sets it, and only from a real
    // PRE_COMMIT QC). "The reset cleared the lock" was therefore indistinguishable from the reset doing
    // NOTHING, and this test passed against a no-op `resetLocalSafetyState`. It now establishes a
    // genuine lock first, so it fails if the reset stops clearing it.
    val (coordinator, cast) = newCoordinator(multiKey = true)
    val blockA              = blockId(7)
    val blockB              = blockId(9)

    // Build a REAL lockedQC on block A at view 5.
    establishLock(coordinator, view = 5, blockId = blockA, height = 100)

    // Prove the lock is genuinely held and genuinely blocking: with `extendsBranch = false`, a
    // conflicting block at a HIGHER view (so `lastVotedView` cannot be what rejects it) must be
    // refused by the lock rule alone. This is the negative control that makes the assertion below
    // meaningful rather than vacuous.
    cast.clear()
    coordinator.onProposal(HotStuffProposal(6, blockB, None), 200)
    cast.map(_.blockId).toList should not contain blockB // locked onto A -> B refused

    // Now the watchdog's recovery fires. Only a genuinely-cleared lock lets B through.
    coordinator.resetLocalSafetyState()

    // A proposal at a STRICTLY HIGHER view is the legitimate post-recovery case. The pacemaker's view
    // (`PacemakerState`, a separate `EngineState` field the reset never touches) keeps advancing on
    // every stalled `onRoundTimerTick`, so real recovery traffic always arrives above `lastVotedView`.
    cast.clear()
    coordinator.onProposal(HotStuffProposal(7, blockB, None), 200)
    cast.map(_.blockId).toList should contain(blockB) // lock genuinely cleared -> recovery works
  }

  it should "keep the view bound monotonic -- the reset never lowers lastVotedView" in {
    val (coordinator, cast) = newCoordinator()
    val blockA              = blockId(7)
    val blockB              = blockId(9)

    coordinator.onProposal(HotStuffProposal(9, blockA, None), 100)
    coordinator.resetLocalSafetyState()

    // Every view at or below the one already voted in must stay barred, not just the exact same view.
    cast.clear()
    (0 to 9).foreach(v => coordinator.onProposal(HotStuffProposal(v, blockB, None), 100))
    cast.toList should be(empty)

    // ...and the very next view above it is admitted, proving the bound is a bound and not a freeze.
    coordinator.onProposal(HotStuffProposal(10, blockB, None), 100)
    cast.map(_.blockId).toList should contain(blockB)
  }

  /** M1 (persist `lastVotedView` across restarts) regression coverage: this is the RESTART analogue of
    * the in-process watchdog scenario above, closing the RESIDUAL GAP that `resetLocalSafetyState`'s doc
    * used to document as deferred. A restarted replica no longer boots blind at `lastVotedView = -1` --
    * `Application.scala` seeds `initialLastVotedView` from `HotStuffLastVotedViewStore.load`, so a fresh
    * `HotStuffCoordinator.Enabled` constructed with a persisted view must refuse to vote again at (or
    * below) that view, exactly like the in-memory bound already does post-reset.
    */
  "a coordinator restarted with a persisted lastVotedView (M1)" should
    "refuse to vote at the SAME view it already voted in before the restart" in {
      val v                   = 5
      val (coordinator, cast) = newCoordinator(initialLastVotedView = v)
      val blockB              = blockId(9)

      coordinator.onProposal(HotStuffProposal(v, blockB, None), 100)

      cast.map(_.blockId).toList should not contain blockB
    }

  it should "still admit a proposal at a STRICTLY HIGHER view than the persisted one" in {
    val v                   = 5
    val (coordinator, cast) = newCoordinator(initialLastVotedView = v)
    val blockB               = blockId(9)

    coordinator.onProposal(HotStuffProposal(v + 1, blockB, None), 100)

    cast.map(_.blockId).toList should contain(blockB)
  }
}
