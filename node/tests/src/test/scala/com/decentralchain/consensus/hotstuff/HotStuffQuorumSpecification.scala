package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffVote, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

class HotStuffQuorumSpecification extends FlatSpec {
  private val view    = 7
  private val phase   = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
  private val blockId = ByteStr(Array.fill[Byte](32)(9))
  private val height  = Height(1000)

  // 4 equal-stake members (25 each; total 100). 2/3 quorum => need >= 67 stake => >= 3 members.
  private val kps: IndexedSeq[BlsKeyPair] =
    (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))

  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, balance = 25L)
  }

  private def vote(i: Int): HotStuffVote = {
    val msg = HotStuffQuorum.voteMessage(view, phase, blockId, height.toInt)
    HotStuffVote(view, phase, blockId, height, i, kps(i).sign(msg).byteStr)
  }

  "verifyVote" should "accept a valid vote and reject tampering / unknown voters" in {
    HotStuffQuorum.verifyVote(vote(0), committee) should be(true)
    HotStuffQuorum.verifyVote(vote(0).copy(blockId = ByteStr(Array.fill[Byte](32)(1))), committee) should be(false)
    HotStuffQuorum.verifyVote(vote(0).copy(voterIndex = 99), committee) should be(false)
  }

  "formQC" should "build a verifiable QC at >= 2/3 stake" in {
    val qc = HotStuffQuorum.formQC(Seq(vote(0), vote(1), vote(2)), committee).toOption.get
    qc.signerIndexes should be(Seq(0, 1, 2))
    HotStuffQuorum.verifyQC(qc, committee) should be(Right(true))
  }

  it should "de-duplicate repeated voters" in {
    val qc = HotStuffQuorum.formQC(Seq(vote(0), vote(0), vote(1), vote(2)), committee).toOption.get
    qc.signerIndexes should be(Seq(0, 1, 2))
  }

  it should "reject a below-quorum vote set" in {
    HotStuffQuorum.formQC(Seq(vote(0), vote(1)), committee).isLeft should be(true) // 50 of 100 < 2/3
  }

  it should "reject votes targeting different blocks" in {
    val other = vote(1).copy(blockId = ByteStr(Array.fill[Byte](32)(2)))
    HotStuffQuorum.formQC(Seq(vote(0), other, vote(2)), committee).isLeft should be(true)
  }

  "verifyQC" should "reject a QC whose signer set is below quorum" in {
    val aggSig = Seq(vote(0), vote(1)).map(_.signature.arr).reduceLeft(BlsUtils.aggSign)
    val badQc  = QuorumCertificate(view, phase, blockId, height, Seq(0, 1), ByteStr(aggSig))
    HotStuffQuorum.verifyQC(badQc, committee).getOrElse(false) should be(false)
  }

  it should "reject a QC with a forged aggregate signature" in {
    val forged = QuorumCertificate(view, phase, blockId, height, Seq(0, 1, 2), ByteStr(Array.fill[Byte](96)(0)))
    HotStuffQuorum.verifyQC(forged, committee).getOrElse(false) should be(false)
  }
}
