package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.TestBlsKeyPair
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

class HotStuffVotePoolSpecification extends FlatSpec {
  private val kps = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private val PREPARE = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
  private val block: BlockId = ByteStr(Array.fill[Byte](32)(9))
  private val height = 100

  private def vote(i: Int): HotStuffVote = {
    val msg = HotStuffQuorum.voteMessage(5, PREPARE, block, height)
    HotStuffVote(5, PREPARE, block, Height(height), i, kps(i).sign(msg).byteStr)
  }

  "onVote" should "accumulate below quorum without emitting a QC" in {
    val (p1, qc1) = HotStuffVotePool.onVote(VotePool(), vote(0), committee)
    qc1 should be(None)
    val (_, qc2) = HotStuffVotePool.onVote(p1, vote(1), committee) // 2/4 = 50% < 2/3
    qc2 should be(None)
  }

  it should "emit a verifiable QC once 2/3 stake is reached, and clear the bucket" in {
    val (p1, _)   = HotStuffVotePool.onVote(VotePool(), vote(0), committee)
    val (p2, _)   = HotStuffVotePool.onVote(p1, vote(1), committee)
    val (p3, qc)  = HotStuffVotePool.onVote(p2, vote(2), committee) // 3/4 = 75% >= 2/3
    qc.isDefined should be(true)
    HotStuffQuorum.verifyQC(qc.get, committee) should be(Right(true))
    p3.pending should be(empty) // bucket cleared on emit
  }

  it should "drop an invalid (forged) vote without pooling it" in {
    val forged = vote(0).copy(signature = ByteStr(Array.fill[Byte](96)(0)))
    val (p, qc) = HotStuffVotePool.onVote(VotePool(), forged, committee)
    qc should be(None)
    p.pending should be(empty)
  }

  it should "not double-count a repeated voter" in {
    val (p1, _)  = HotStuffVotePool.onVote(VotePool(), vote(0), committee)
    val (p2, _)  = HotStuffVotePool.onVote(p1, vote(0), committee) // same voter again
    p2.pending.values.flatten.map(_.voterIndex).toList should be(List(0))
  }

  it should "pool distinct targets separately" in {
    val other: BlockId = ByteStr(Array.fill[Byte](32)(7))
    val voteOther = {
      val msg = HotStuffQuorum.voteMessage(5, PREPARE, other, height)
      HotStuffVote(5, PREPARE, other, Height(height), 1, kps(1).sign(msg).byteStr)
    }
    val (p1, _) = HotStuffVotePool.onVote(VotePool(), vote(0), committee)
    val (p2, _) = HotStuffVotePool.onVote(p1, voteOther, committee)
    p2.pending.keySet.size should be(2)
  }
}
