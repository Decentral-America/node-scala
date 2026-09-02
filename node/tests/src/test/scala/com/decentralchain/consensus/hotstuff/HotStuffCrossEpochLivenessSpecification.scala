package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsSignature, BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffVote, Message}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

import scala.collection.mutable

/** SAFETY/LIVENESS-CRITICAL FINDING (2026-08-04, two independent adversarial reviews) -- a NEW hazard
  * distinct from `HotStuffCrossEpochForkSpecification`'s T10 fork hazard (disjoint committees signing
  * different blocks) and from `HotStuffVotePoolCommitteeChangeSpecification`'s mid-accumulation
  * committee-change hazard.
  *
  * Root cause (pre-fix): `committeeEpoch` was derived (see `Application.scala`) from
  * `blockchainUpdater.currentGenerationPeriod` -- i.e. the SIGNING REPLICA'S OWN LOCAL TIP HEIGHT at
  * the moment it casts a vote -- instead of from the vote TARGET's own agreed `blockHeight`.
  * Consequence: two fully HONEST, fully-synced replicas voting for the IDENTICAL `(view, phase,
  * blockId, blockHeight)` target could sign DIFFERENT `committeeEpoch` values if their local tip
  * straddled a generation-period boundary at slightly different moments -- ordinary propagation skew,
  * not an attack. `HotStuffQuorum.formQC`'s `sameTarget` check (which the T10 fix correctly made
  * epoch-sensitive) then rejected that mixed bucket outright: a genuine liveness stall at every
  * committee-epoch rotation boundary, previously uncharacterized.
  *
  * PROOF THE BUG WAS REAL (RED, captured before the fix below existed -- see PR history for the actual
  * failing/bug-confirming run): with `HotStuffCoordinator.Enabled`'s (then-only) `committeeEpochProvider`
  * wired directly to each replica's own differing local-tip belief (5 vs 6) and used for vote-signing,
  * two honest replicas voting the identical target signed `committeeEpoch=5` and `committeeEpoch=6`
  * respectively, and `HotStuffQuorum.formQC` on the resulting 3-vote (quorum-sized) set returned `Left`
  * -- a QC that should have formed, did not.
  *
  * THE FIX (see `HotStuffCoordinator.Enabled`'s `committeeEpochOf` parameter and `Application.scala`'s
  * wiring): the epoch a vote is SIGNED under is now derived as a PURE function of the vote's TARGET
  * height (`blockchain.generationPeriodOf(targetHeight).index` in production), not the signer's live
  * tip. Since `generationPeriodOf` for a FIXED height always returns the same result regardless of when
  * it is computed, every honest replica voting on the SAME target now deterministically computes the
  * SAME epoch, closing the stall at its root rather than merely bounding it. `committeeEpochProvider`
  * (the pre-existing closure) remains, but is now used ONLY for the transition-gating decision on
  * INCOMING QCs/proposals (`HotStuffEngine.onQC`/`onProposal` via `HotStuffQuorum.acceptableCommitteeEpoch`)
  * -- never for what a replica itself signs.
  *
  * `HotStuffVotePool.onVote`'s per-voter dedup is ALSO made epoch-aware (keyed on
  * `(voterIndex, committeeEpoch)`, not `voterIndex` alone) as defense-in-depth against any remaining
  * edge case (e.g. a genuinely Byzantine relabeled-epoch vote), without relying solely on the upstream
  * fix being perfect everywhere.
  *
  * The two tests below now assert the FIXED (GREEN) behaviour end-to-end.
  */
class HotStuffCrossEpochLivenessSpecification extends FlatSpec {
  private val kps = (0 until 3).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))

  private def generator(i: Int, stake: Long): GeneratorInfo =
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kps(i).publicKey, stake)

  // 3 equal-stake members, total 75, 2/3 threshold = 50 (need 2-of-3).
  private val committee: GeneratorSet = Seq(generator(0, 25), generator(1, 25), generator(2, 25))

  private val PREPARE          = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
  private val view             = 7
  private val height           = 1000
  private val blockId: BlockId = ByteStr(Array.fill[Byte](32)(0xcc.toByte))

  /** One coordinator per replica. `committeeEpochProvider` is fixed per-instance to simulate that
    * replica's OWN local-tip-derived epoch belief (post-fix, used ONLY for the transition-gating
    * decision on incoming QCs -- never for what this replica itself signs). `committeeEpochOf` is the
    * ROOT-CAUSE fix's pure, height-derived function -- shared identically by both replicas below,
    * exactly as production wiring would (both nodes run the same deterministic `generationPeriodOf`
    * computation against the same fixed chain constants).
    */
  private def replicaCoordinator(
      voterIdx: Int,
      localTipEpochBelief: Int,
      committeeEpochOf: Int => Int,
      sent: mutable.ListBuffer[Message]
  ): HotStuffCoordinator.Enabled = {
    val fx = new HotStuffEffects {
      def broadcast(m: Message): Unit                                = sent += m
      def myVoterIndexes: Set[Int]                                   = Set(voterIdx)
      def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = if (idx == voterIdx) Some(kps(voterIdx).sign(msg, BlsUtils.BlsDomainSeparationTag)) else None
      def onCommit(blockId: BlockId, height: Int): Unit              = ()
      def onEquivocation(proof: HotStuffEquivocationProof): Unit     = ()
    }
    new HotStuffCoordinator.Enabled(
      committeeProvider = () => committee,
      effects = fx,
      extendsBranch = (_, _) => true,
      committeeEpochProvider = () => localTipEpochBelief,
      committeeEpochOf = committeeEpochOf
    )
  }

  "two honest, fully-synced replicas whose local tip straddles a generation-period boundary at slightly different moments" should
    "nonetheless sign the SAME (view, phase, blockId, blockHeight) target with the SAME committeeEpoch, because it is derived from the target's own height (a pure, fixed-chain-constant function), not from either replica's differing live-tip belief -- and formQC now forms a QC (the fix)" in {
      val sentA = mutable.ListBuffer.empty[Message]
      val sentB = mutable.ListBuffer.empty[Message]

      // A pure, height-derived epoch function -- exactly the shape of `Application.scala`'s
      // `committeeEpochOf` (`blockchain.generationPeriodOf(h).index`): identical on every replica,
      // depends only on the (fixed) target height, never on when/who computes it.
      val committeeEpochOf: Int => Int = h => h / 500

      // Replica A believes (at ITS OWN live tip) epoch 5 is active; replica B already believes epoch 6
      // is active -- ordinary propagation skew, nobody is Byzantine. Post-fix, `committeeEpochProvider`
      // no longer feeds vote-signing at all -- only `committeeEpochOf(targetHeight)` does -- so this
      // differing local belief no longer matters for what gets signed.
      val replicaA = replicaCoordinator(voterIdx = 0, localTipEpochBelief = 5, committeeEpochOf, sentA)
      val replicaB = replicaCoordinator(voterIdx = 1, localTipEpochBelief = 6, committeeEpochOf, sentB)

      import com.decentralchain.network.HotStuffProposal
      val proposal = HotStuffProposal(view, blockId, None)
      replicaA.onProposal(proposal, height)
      replicaB.onProposal(proposal, height)

      val voteA = sentA.collectFirst { case v: HotStuffVote => v }.getOrElse(fail("replica A did not vote"))
      val voteB = sentB.collectFirst { case v: HotStuffVote => v }.getOrElse(fail("replica B did not vote"))

      voteA.view should be(voteB.view)
      voteA.phase should be(voteB.phase)
      voteA.blockId should be(voteB.blockId)
      voteA.blockHeight should be(voteB.blockHeight)

      // THE FIX: despite differing local-tip epoch beliefs (5 vs 6), both replicas sign the IDENTICAL
      // committeeEpoch, because it is derived purely from the shared target height.
      voteA.committeeEpoch should be(voteB.committeeEpoch)
      voteA.committeeEpoch should be(committeeEpochOf(height))

      // A third replica votes the same (now-consistent) epoch to reach the 2-of-3 quorum.
      val msgC  = HotStuffQuorum.voteMessage(view, PREPARE, blockId, height, committeeEpochOf(height))
      val voteC = HotStuffVote(view, PREPARE, blockId, Height(height), 2, kps(2).sign(msgC, BlsUtils.BlsDomainSeparationTag).byteStr, committeeEpochOf(height))

      // formQC now SUCCEEDS -- the mixed-local-belief scenario that used to permanently stall this
      // target no longer applies to ordinary honest propagation skew, because the signed epoch never
      // depends on it in the first place. (`HotStuffCrossEpochForkSpecification`'s disjoint-epoch
      // `formQC` rejection test proves the genuinely-different-epoch/Byzantine case is still correctly
      // enforced -- this fix narrows WHEN two honest votes can legitimately differ in epoch at all, it
      // does not weaken `sameTarget`'s check itself.)
      HotStuffQuorum.formQC(Seq(voteA, voteB, voteC), committee) shouldBe a[Right[?, ?]]
    }

  "HotStuffVotePool.onVote's per-voter dedup" should
    "no longer silently drop a voter's genuine same-target vote cast under a newer epoch just because that voter's FIRST vote for the target was already pooled under an older epoch (reviewer B's defense-in-depth fix)" in {
      val oldEpoch = 5
      val newEpoch = 6

      def voteFor(voterIdx: Int, epoch: Int): HotStuffVote = {
        val msg = HotStuffQuorum.voteMessage(view, PREPARE, blockId, height, epoch)
        HotStuffVote(view, PREPARE, blockId, Height(height), voterIdx, kps(voterIdx).sign(msg, BlsUtils.BlsDomainSeparationTag).byteStr, epoch)
      }

      // Voter 0's FIRST vote for this target lands under a stale epoch (e.g. a genuinely Byzantine
      // relabeled-epoch vote, or any other remaining edge case the root-cause fix doesn't itself rule
      // out at the pool layer).
      val (afterStale, _) = HotStuffVotePool.onVote(VotePool(), voteFor(0, oldEpoch), committee)

      // Voter 0 later casts a GENUINE vote for the SAME target under the current epoch.
      val (afterGenuine, _) = HotStuffVotePool.onVote(afterStale, voteFor(0, newEpoch), committee)

      val key    = (view, PREPARE, blockId)
      val bucket = afterGenuine.pending.getOrElse(key, Vector.empty)

      // THE FIX: dedup is now keyed on `(voterIndex, committeeEpoch)`, so voter 0's genuine newEpoch
      // vote is retained as a DISTINCT entry rather than being silently discarded because voter 0
      // "already voted" (under a different epoch). Note `formQC`'s `sameTarget` check still (correctly,
      // by design -- the T10 fork-hazard fix) refuses to merge a bucket whose entries disagree on
      // `committeeEpoch` into one QC; this fix's job is only to stop the pool from silently forgetting
      // a genuine vote, not to make mixed-epoch buckets form a QC.
      bucket.size should be(2)
      bucket.map(_.committeeEpoch).toSet should be(Set(oldEpoch, newEpoch))
    }
}
