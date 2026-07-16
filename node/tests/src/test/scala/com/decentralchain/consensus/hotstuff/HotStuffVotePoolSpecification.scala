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
  private val kps                     = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private val PREPARE        = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
  private val block: BlockId = ByteStr(Array.fill[Byte](32)(9))
  private val height         = 100

  private def vote(i: Int): HotStuffVote = voteAtHeight(i, height)

  private def voteAtHeight(i: Int, h: Int): HotStuffVote = {
    val msg = HotStuffQuorum.voteMessage(5, PREPARE, block, h)
    HotStuffVote(5, PREPARE, block, Height(h), i, kps(i).sign(msg).byteStr)
  }

  "onVote" should "accumulate below quorum without emitting a QC" in {
    val (p1, qc1) = HotStuffVotePool.onVote(VotePool(), vote(0), committee)
    qc1 should be(None)
    val (_, qc2) = HotStuffVotePool.onVote(p1, vote(1), committee) // 2/4 = 50% < 2/3
    qc2 should be(None)
  }

  it should "emit a verifiable QC once 2/3 stake is reached, and clear the bucket" in {
    val (p1, _)  = HotStuffVotePool.onVote(VotePool(), vote(0), committee)
    val (p2, _)  = HotStuffVotePool.onVote(p1, vote(1), committee)
    val (p3, qc) = HotStuffVotePool.onVote(p2, vote(2), committee) // 3/4 = 75% >= 2/3
    qc.isDefined should be(true)
    HotStuffQuorum.verifyQC(qc.get, committee) should be(Right(true))
    p3.pending should be(empty) // bucket cleared on emit
  }

  it should "drop an invalid (forged) vote without pooling it" in {
    val forged  = vote(0).copy(signature = ByteStr(Array.fill[Byte](96)(0)))
    val (p, qc) = HotStuffVotePool.onVote(VotePool(), forged, committee)
    qc should be(None)
    p.pending should be(empty)
  }

  it should "not double-count a repeated voter" in {
    val (p1, _) = HotStuffVotePool.onVote(VotePool(), vote(0), committee)
    val (p2, _) = HotStuffVotePool.onVote(p1, vote(0), committee) // same voter again
    p2.pending.values.flatten.map(_.voterIndex).toList should be(List(0))
  }

  // Regression (step-5 live bug, 2026-07-12): votes agreeing on (view, phase, blockId) but signed over
  // DIFFERENT blockHeights land in the same bucket and reach the voter-count quorum, yet `formQC`'s
  // sameTarget check rejects them, so no QC forms. This silently blocked every testnet commit. The shell
  // fix makes every replica vote over the settled-view height so this cannot happen in production; this
  // test pins the pool contract so a future regression is caught here instead of on a live network.
  it should "NOT form a QC when quorum-many voters disagree on blockHeight" in {
    val (p1, _)  = HotStuffVotePool.onVote(VotePool(), voteAtHeight(0, 100), committee)
    val (p2, _)  = HotStuffVotePool.onVote(p1, voteAtHeight(1, 103), committee)
    val (p3, qc) = HotStuffVotePool.onVote(p2, voteAtHeight(2, 103), committee) // 3/4 voters, but mixed heights
    qc should be(None)
    p3.pending.values.flatten.map(_.voterIndex).toSet should be(Set(0, 1, 2)) // all pooled, none dropped
  }

  it should "form a QC once quorum-many voters agree on the SAME blockHeight" in {
    val (p1, _) = HotStuffVotePool.onVote(VotePool(), voteAtHeight(0, 103), committee)
    val (p2, _) = HotStuffVotePool.onVote(p1, voteAtHeight(1, 103), committee)
    val (_, qc) = HotStuffVotePool.onVote(p2, voteAtHeight(2, 103), committee)
    qc.isDefined should be(true)
    HotStuffQuorum.verifyQC(qc.get, committee) should be(Right(true))
  }

  it should "pool distinct targets separately" in {
    val other: BlockId = ByteStr(Array.fill[Byte](32)(7))
    val voteOther      = {
      val msg = HotStuffQuorum.voteMessage(5, PREPARE, other, height)
      HotStuffVote(5, PREPARE, other, Height(height), 1, kps(1).sign(msg).byteStr)
    }
    val (p1, _) = HotStuffVotePool.onVote(VotePool(), vote(0), committee)
    val (p2, _) = HotStuffVotePool.onVote(p1, voteOther, committee)
    p2.pending.keySet.size should be(2)
  }
}
