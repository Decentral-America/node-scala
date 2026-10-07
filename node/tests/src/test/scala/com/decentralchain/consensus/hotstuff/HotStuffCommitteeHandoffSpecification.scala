package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.{Address, KeyPair}
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsSignature, BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

import scala.collection.mutable

/** Live testnet regression, 2026-10-04..06 (TESTNET-FINAL-PLAN "RC2"): at EVERY committee membership change
  * HotStuff froze at the previous period's x97 until the WATCHDOG cleared the lock (24 resets, all four
  * nodes within 1s, all 0-5 blocks after a period boundary). Logs: `QC rejected: QC references unknown
  * committee member` (new committee smaller) and `QC rejected: Wrong BLS signature` (h=33897, committee
  * {main=#0, gen-1=#1} -> {gen-1=#0}).
  *
  * Root cause: votes and QCs are signed under the TARGET block's epoch (`committeeEpochOf(targetHeight)`),
  * and `acceptableCommitteeEpoch` accepts the previous epoch during a rotation -- but signer indexes were
  * resolved, and signatures verified, against the CURRENT tip's committee (`engine.committee`). An index of
  * the old committee then names a different member, or none, of the new one.
  *
  * The rule these tests pin: a vote or QC is resolved against the committee of its TARGET height's
  * generation period, everywhere (signing, pooling, QC verification, justify verification).
  */
class HotStuffCommitteeHandoffSpecification extends FlatSpec {
  private val kps                           = (0 until 2).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private def address(i: Int): Address      = KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress
  private def info(member: Int, index: Int) = GeneratorInfo(GeneratorIndex(index), address(member), kps(member).publicKey, 25)

  private val PeriodLength        = 100
  private val epochOf: Int => Int = h => (h - 1) / PeriodLength

  // Epoch 33 (heights 3301..3400): {g0 = #0, g1 = #1}. Epoch 34 (3401..3500): {g1 = #0}.
  private val oldCommittee: GeneratorSet       = Seq(info(0, 0), info(1, 1))
  private val newCommittee: GeneratorSet       = Seq(info(1, 0))
  private val committeeAt: Int => GeneratorSet = h => if (epochOf(h) == 33) oldCommittee else newCommittee

  private val oldTarget        = 3397 // the frozen "x97" height
  private val blockId: BlockId = ByteStr(Array.fill[Byte](32)(0xab.toByte))
  private val COMMIT           = HotStuffPhase.HOTSTUFF_PHASE_COMMIT
  private val PREPARE          = HotStuffPhase.HOTSTUFF_PHASE_PREPARE

  private def vote(member: Int, index: Int, view: Int, phase: HotStuffPhase, height: Int): HotStuffVote = {
    val msg = HotStuffQuorum.voteMessage(view, phase, blockId, height, epochOf(height))
    HotStuffVote(view, phase, blockId, Height(height), index, kps(member).sign(msg, BlsUtils.BlsHsVoteDomainSeparationTag).byteStr, epochOf(height))
  }

  /** A replica whose tip is already in epoch 34, holding the BLS keys of `members`. */
  private def replica(members: Set[Int], sent: mutable.ListBuffer[Message], commits: mutable.ListBuffer[Int]): HotStuffCoordinator.Enabled = {
    val keys: Map[Address, BlsKeyPair] = members.map(m => address(m) -> kps(m)).toMap
    val fx                             = new HotStuffEffects {
      def broadcast(m: Message): Unit = sent += m
      // Legacy single-committee view, kept for callers that do not know the target committee.
      def myVoterIndexes: Set[Int]                                                = myVoterIndexesIn(newCommittee)
      def signVote(msg: Array[Byte], idx: Int, dst: String): Option[BlsSignature] = signVoteIn(newCommittee, msg, idx, dst)
      override def myVoterIndexesIn(committee: GeneratorSet): Set[Int]            =
        committee.iterator.filter(g => keys.contains(g.address)).map(_.index.toInt).toSet
      override def signVoteIn(committee: GeneratorSet, msg: Array[Byte], idx: Int, dst: String): Option[BlsSignature] =
        committee.find(_.index.toInt == idx).flatMap(g => keys.get(g.address)).map(_.sign(msg, dst))
      def onCommit(blockId: BlockId, height: Int): Unit          = commits += height
      def onEquivocation(proof: HotStuffEquivocationProof): Unit = ()
    }
    new HotStuffCoordinator.Enabled(
      committeeProvider = () => newCommittee,
      effects = fx,
      extendsBranch = (_, _) => true,
      committeeEpochProvider = () => 34,
      committeeEpochOf = epochOf,
      committeeAt = Some(committeeAt)
    )
  }

  "a COMMIT QC formed by the PREVIOUS epoch's committee" should "be verified against that committee and commit, not be rejected as 'unknown committee member'" in {
    val qc = HotStuffQuorum
      .formQC(Seq(vote(0, 0, 10, COMMIT, oldTarget), vote(1, 1, 10, COMMIT, oldTarget)), oldCommittee)
      .fold(e => fail(s"test setup: old committee must form the QC: $e"), identity)

    withClue("precondition (the live failure): against the NEW committee this QC does not verify: ") {
      HotStuffQuorum.verifyQC(qc, newCommittee).isLeft shouldBe true
    }

    val commits = mutable.ListBuffer.empty[Int]
    replica(Set(1), mutable.ListBuffer.empty, commits).onQC(qc)
    commits.toSeq shouldBe Seq(oldTarget)
  }

  "votes for an old-epoch target received after the rotation" should "be pooled against the target's committee and form a QC" in {
    val commits = mutable.ListBuffer.empty[Int]
    val r       = replica(Set(1), mutable.ListBuffer.empty, commits)
    r.onVote(vote(0, 0, 11, COMMIT, oldTarget))
    r.onVote(vote(1, 1, 11, COMMIT, oldTarget))
    commits.toSeq shouldBe Seq(oldTarget)
  }

  "a replica voting for an old-epoch target after the rotation" should "sign with its index in the TARGET's committee" in {
    val sent = mutable.ListBuffer.empty[Message]
    // g0 is only in the OLD committee: before the fix it found no index in the current committee and never voted.
    replica(Set(0), sent, mutable.ListBuffer.empty).onProposal(HotStuffProposal(12, blockId, None), oldTarget)
    val v = sent.collectFirst { case v: HotStuffVote if v.phase == PREPARE => v }.getOrElse(fail("g0 did not vote for the old-epoch target"))
    v.voterIndex shouldBe 0
    v.committeeEpoch shouldBe 33
    HotStuffQuorum.verifyVote(v, oldCommittee) shouldBe true
  }
}
