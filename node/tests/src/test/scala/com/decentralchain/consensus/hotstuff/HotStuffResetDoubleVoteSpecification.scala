package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsSignature, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}
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
    */
  private def newCoordinator(): (HotStuffCoordinator.Enabled, scala.collection.mutable.ListBuffer[HotStuffVote]) = {
    val cast = scala.collection.mutable.ListBuffer.empty[HotStuffVote]
    val fx   = new HotStuffEffects {
      def broadcast(m: Message): Unit = m match {
        case v: HotStuffVote => cast += v
        case _               => ()
      }
      def myVoterIndexes: Set[Int]                                   = Set(0)
      def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = Option.when(idx == 0)(kps(0).sign(msg))
      def onCommit(blockId: BlockId, height: Int): Unit              = ()
    }
    val c = new HotStuffCoordinator.Enabled(
      committeeProvider = () => committee,
      effects = fx,
      // No block extends any other: isolates the `lastVotedView` guard from the lock/liveness branch,
      // so a pass/fail here is unambiguously about the view bound this finding is about.
      extendsBranch = (_, _) => false
    )
    (c, cast)
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
    val (coordinator, cast) = newCoordinator()
    val blockA              = blockId(7)
    val blockB              = blockId(9)

    coordinator.onProposal(HotStuffProposal(5, blockA, None), 100)
    coordinator.resetLocalSafetyState()

    // A proposal at a STRICTLY HIGHER view is the legitimate post-recovery case. The pacemaker's view
    // (`PacemakerState`, a separate `EngineState` field the reset never touches) keeps advancing on
    // every stalled `onRoundTimerTick`, so real recovery traffic always arrives above `lastVotedView`.
    cast.clear()
    coordinator.onProposal(HotStuffProposal(6, blockB, None), 200)
    cast.map(_.blockId).toList should contain(blockB) // recovery still works: lock genuinely cleared
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
}
