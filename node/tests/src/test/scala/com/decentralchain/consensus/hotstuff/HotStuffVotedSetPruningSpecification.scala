package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsSignature, BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffVote, Message, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

/** Audit finding F-9 (LOW) regression coverage: `private var voted` (`HotStuffCoordinator.scala`) grew
  * without bound -- added to on every `castVotes` call, never reclaimed. `prunePool()` already reclaims
  * `pool`/`seenCommittees` by view on every view-advance; `voted` was missed by that sweep.
  *
  * The fix prunes `voted` alongside `prunePool()`, using the SAME `view >= pacemaker.view - 1`
  * retention margin the pool uses -- pruning tighter would re-admit duplicate votes for a still-live
  * target, which is exactly what the pool's own one-view margin exists to avoid (see `prunePool`'s doc).
  *
  * There is no direct accessor for `voted`'s contents, so this spec proves pruning happened
  * BEHAVIORALLY, via the phase-progression vote path (`applyQC`'s `castVotes(qc.view, nextPhase, ...)`),
  * which is guarded ONLY by `voted` -- unlike the PREPARE-phase path, it is NOT also gated by
  * `lastVotedView`/`safeToVote`, so re-casting (or not) isolates `voted`'s own guard/prune behaviour
  * cleanly. A second, freshly-formed PREPARE QC for the SAME OLD `(view, phase=PREPARE, blockId)`
  * target casts no PRE_COMMIT vote while the guard entry is retained; once that view has aged out past
  * the pool's own retention margin, the guard entry is gone and the identical target's PRE_COMMIT vote
  * is (re-)cast -- which is safe precisely because the live-target vote pool (pruned by the identical
  * margin) independently prevents a stray duplicate vote for an old, already-pruned target from ever
  * contributing to a new QC.
  */
class HotStuffVotedSetPruningSpecification extends FlatSpec {
  private val kps                     = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }

  private def blockId(b: Byte): BlockId = ByteStr(Array.fill[Byte](32)(b))

  private def newCoordinator(): (HotStuffCoordinator.Enabled, scala.collection.mutable.ListBuffer[HotStuffVote]) = {
    val cast = scala.collection.mutable.ListBuffer.empty[HotStuffVote]
    val fx   = new HotStuffEffects {
      def broadcast(m: Message): Unit = m match {
        case v: HotStuffVote => cast += v
        case _               => ()
      }
      // This replica holds committee slot 3's key (indexes 0-2 are the OTHER signers whose votes
      // `prepareQC` bundles into the PREPARE QC fed via `onQC` below) -- needed so `castVotes`'s
      // phase-progression call actually signs+broadcasts a PRE_COMMIT vote for this spec to observe;
      // with an empty `myVoterIndexes`, `castVotes`'s `mine.foreach` never iterates and nothing is ever
      // cast, regardless of `voted`'s state.
      def myVoterIndexes: Set[Int]                                                = Set(3)
      def signVote(msg: Array[Byte], idx: Int, dst: String): Option[BlsSignature] = Option.when(idx == 3)(kps(3).sign(msg, dst))
      def onCommit(blockId: BlockId, height: Int): Unit                           = ()
      def onEquivocation(proof: HotStuffEquivocationProof): Unit                  = ()
    }
    val c = new HotStuffCoordinator.Enabled(
      committeeProvider = () => committee,
      effects = fx,
      extendsBranch = (_, _) => true
    )
    (c, cast)
  }

  /** A real, quorum-backed PREPARE QC for `(view, blockId, height)` -- 3-of-4 signers, genuinely
    * `verifyQC`-valid. Feeding this via `coordinator.onQC` drives `applyQC`'s phase-progression branch,
    * which casts a PRE_COMMIT vote for the SAME target via `castVotes(qc.view, PRE_COMMIT, ...)`,
    * guarded solely by `voted`.
    */
  private def prepareQC(view: Int, blockId: BlockId, height: Int): QuorumCertificate = {
    val msg   = HotStuffQuorum.voteMessage(view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockId, height)
    val votes = (0 to 2).map(i =>
      HotStuffVote(
        view,
        HotStuffPhase.HOTSTUFF_PHASE_PREPARE,
        blockId,
        Height(height),
        i,
        kps(i).sign(msg, BlsUtils.BlsHsVoteDomainSeparationTag).byteStr
      )
    )
    HotStuffQuorum.formQC(votes, committee).toOption.get
  }

  private def preCommitVotesFor(cast: scala.collection.mutable.ListBuffer[HotStuffVote], view: Int, blockId: BlockId): Int =
    cast.count(v => v.view == view && v.phase == HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT && v.blockId == blockId)

  "the voted set" should "be pruned by view, alongside prunePool(), so an old (view, phase, blockId) " +
    "phase-progression target can be re-voted for after the view has advanced well past the pool's " +
    "retention margin (audit F-9)" in {
      val (coordinator, cast) = newCoordinator()
      val b1                  = blockId(7)

      // A real PREPARE QC at view 0 for b1: applyQC's phase-progression casts a PRE_COMMIT vote for
      // (0, PRE_COMMIT, b1). `voted` now contains that key. `HotStuffEngine.onQC` also unconditionally
      // advances the pacemaker on any valid QC (`HotStuffPacemaker.onQC`'s `qcView + 1`), so
      // `currentView` is already 1 immediately after this call, not 0.
      coordinator.onQC(prepareQC(view = 0, blockId = b1, height = 100))
      preCommitVotesFor(cast, view = 0, b1) should be(1)
      coordinator.currentView should be(1)

      // Re-delivering the SAME PREPARE QC while the entry is still retained casts no second PRE_COMMIT
      // vote for the same target -- `voted` is doing its job.
      cast.clear()
      coordinator.onQC(prepareQC(view = 0, blockId = b1, height = 100))
      preCommitVotesFor(cast, view = 0, b1) should be(0)

      // Advance the pacemaker view well past view 0 via repeated timeouts, so `prunePool()` (called from
      // `onTimeout`) evicts the `view=0` entry under the `view >= pacemaker.view - 1` margin. Each
      // `onTimeout()` bumps the view by exactly one (`HotStuffPacemaker.onTimeout`); starting from
      // view 1 (see above), 10 timeouts land at view 11.
      (1 to 10).foreach(_ => coordinator.onTimeout())
      coordinator.currentView should be(11)

      // The SAME (view=0, PRE_COMMIT, b1) target is voted for again: the guard entry was pruned, not
      // merely "still there but somehow bypassed". Re-voting an old, pruned target is safe/acceptable
      // per the audit because the vote pool's own dedup (pruned by the identical margin) independently
      // prevents it from ever contributing to a new QC.
      cast.clear()
      coordinator.onQC(prepareQC(view = 0, blockId = b1, height = 100))
      preCommitVotesFor(cast, view = 0, b1) should be(1)
    }

  it should "NOT re-admit a duplicate phase-progression vote for a target still inside the pool's " +
    "one-view retention margin" in {
      val (coordinator, cast) = newCoordinator()
      val b1                  = blockId(9)

      // View 5: a real PREPARE QC -- PRE_COMMIT cast for (5, PRE_COMMIT, b1). `HotStuffPacemaker.onQC`
      // unconditionally advances the pacemaker to view+1 (6) on any valid QC, so immediately after this
      // call `prunePool`'s margin is `view >= pacemaker.view - 1` = `view >= 5` -- the view-5 entry just
      // written is exactly AT that margin (still retained, by construction: a target's own
      // freshly-cast vote can never immediately evict itself).
      (1 to 5).foreach(_ => coordinator.onTimeout())
      coordinator.currentView should be(5)
      coordinator.onQC(prepareQC(view = 5, blockId = b1, height = 100))
      preCommitVotesFor(cast, view = 5, b1) should be(1)
      coordinator.currentView should be(6)

      // Re-delivering the SAME PREPARE QC right away, with no further view-advance in between, must
      // NOT re-admit a duplicate PRE_COMMIT vote -- the guard entry is still retained (view 5 >= the
      // margin of 5), proving `voted` isn't pruned out from under a still-live target on the very next
      // event.
      cast.clear()
      coordinator.onQC(prepareQC(view = 5, blockId = b1, height = 100))
      preCommitVotesFor(cast, view = 5, b1) should be(0)
    }
}
