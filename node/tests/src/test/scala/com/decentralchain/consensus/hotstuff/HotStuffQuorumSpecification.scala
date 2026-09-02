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

  private def vote(i: Int, dst: String = BlsUtils.BlsDomainSeparationTag): HotStuffVote = {
    val msg = HotStuffQuorum.voteMessage(view, phase, blockId, height.toInt)
    HotStuffVote(view, phase, blockId, height, i, kps(i).sign(msg, dst).byteStr)
  }

  "verifyVote" should "accept a valid vote and reject tampering / unknown voters" in {
    HotStuffQuorum.verifyVote(vote(0), committee, cryptoV2 = false) should be(true)
    HotStuffQuorum.verifyVote(vote(0).copy(blockId = ByteStr(Array.fill[Byte](32)(1))), committee, cryptoV2 = false) should be(false)
    HotStuffQuorum.verifyVote(vote(0).copy(voterIndex = 99), committee, cryptoV2 = false) should be(false)
  }

  // Task 7 (audit H2 hard switch): verifyVote/verifyQC/formQC now take a mandatory `cryptoV2` flag
  // selecting the vote DST (`_HSVOTE_` v2 vs the legacy shared `_NUL_` tag). No default -- this is
  // pure, safety-critical quorum logic; a default here is exactly the trap H2 describes.
  "verifyVote (DST switch)" should "accept a vote signed under _HSVOTE_ when cryptoV2 = true, and reject a legacy-signed vote" in {
    val v2Vote = vote(0, BlsUtils.BlsHsVoteDomainSeparationTagV2)
    HotStuffQuorum.verifyVote(v2Vote, committee, cryptoV2 = true) should be(true)

    val legacyVote = vote(0, BlsUtils.BlsDomainSeparationTag)
    HotStuffQuorum.verifyVote(legacyVote, committee, cryptoV2 = true) should be(false)
  }

  it should "accept a legacy-signed vote when cryptoV2 = false, and reject an _HSVOTE_-signed vote" in {
    val legacyVote = vote(0, BlsUtils.BlsDomainSeparationTag)
    HotStuffQuorum.verifyVote(legacyVote, committee, cryptoV2 = false) should be(true)

    val v2Vote = vote(0, BlsUtils.BlsHsVoteDomainSeparationTagV2)
    HotStuffQuorum.verifyVote(v2Vote, committee, cryptoV2 = false) should be(false)
  }

  it should "reject a v2 PoP signature offered as a vote signature over the same bytes (transplant, audit H2)" in {
    val msg          = HotStuffQuorum.voteMessage(view, phase, blockId, height.toInt)
    val popSignature = kps(0).sign(msg, BlsUtils.BlsPopDomainSeparationTagV2)
    val transplanted = HotStuffVote(view, phase, blockId, height, 0, popSignature.byteStr)
    HotStuffQuorum.verifyVote(transplanted, committee, cryptoV2 = true) should be(false)
  }

  "formQC" should "build a verifiable QC at >= 2/3 stake" in {
    val qc = HotStuffQuorum.formQC(Seq(vote(0), vote(1), vote(2)), committee, cryptoV2 = false).toOption.get
    qc.signerIndexes should be(Seq(0, 1, 2))
    HotStuffQuorum.verifyQC(qc, committee, cryptoV2 = false) should be(Right(()))
  }

  it should "de-duplicate repeated voters" in {
    val qc = HotStuffQuorum.formQC(Seq(vote(0), vote(0), vote(1), vote(2)), committee, cryptoV2 = false).toOption.get
    qc.signerIndexes should be(Seq(0, 1, 2))
  }

  it should "reject a below-quorum vote set" in {
    HotStuffQuorum.formQC(Seq(vote(0), vote(1)), committee, cryptoV2 = false).isLeft should be(true) // 50 of 100 < 2/3
  }

  it should "reject votes targeting different blocks" in {
    val other = vote(1).copy(blockId = ByteStr(Array.fill[Byte](32)(2)))
    HotStuffQuorum.formQC(Seq(vote(0), other, vote(2)), committee, cryptoV2 = false).isLeft should be(true)
  }

  it should "build a QC over _HSVOTE_-signed votes that verifies under cryptoV2 = true but not cryptoV2 = false" in {
    val v2Votes = Seq(vote(0, BlsUtils.BlsHsVoteDomainSeparationTagV2), vote(1, BlsUtils.BlsHsVoteDomainSeparationTagV2), vote(2, BlsUtils.BlsHsVoteDomainSeparationTagV2))
    val qc      = HotStuffQuorum.formQC(v2Votes, committee, cryptoV2 = true).toOption.get
    HotStuffQuorum.verifyQC(qc, committee, cryptoV2 = true) should be(Right(()))
    HotStuffQuorum.verifyQC(qc, committee, cryptoV2 = false).isLeft should be(true)
  }

  "verifyQC" should "reject a QC whose signer set is below quorum" in {
    val aggSig = Seq(vote(0), vote(1)).map(_.signature.arr).reduceLeft((a, b) => BlsUtils.aggSign(a, b).toOption.get)
    val badQc  = QuorumCertificate(view, phase, blockId, height, Seq(0, 1), ByteStr(aggSig))
    HotStuffQuorum.verifyQC(badQc, committee, cryptoV2 = false).isLeft should be(true)
  }

  it should "reject a QC with a forged aggregate signature" in {
    val forged = QuorumCertificate(view, phase, blockId, height, Seq(0, 1, 2), ByteStr(Array.fill[Byte](96)(0)))
    HotStuffQuorum.verifyQC(forged, committee, cryptoV2 = false).isLeft should be(true)
  }

  // audit M4: QuorumCertificate.signerIndexes is a wire-deserialized Seq[Int] with no distinctness
  // guarantee of its own (unlike formQC's own output, which de-dupes per voter before this point) --
  // verifyQC maps it straight into BlsUtils.verifyAgg's pubkey list, UN-de-duplicated. hasQuorum, by
  // contrast, computes stake over signerIndexes.toSet, so a repeated index alone can't inflate the
  // quorum check itself -- but it DOES change what verifyAgg's aggregated-public-key check actually
  // asserts (one repeated key's aggregate contribution is counted twice against a signature that
  // only reflects the real, distinct signers). Signers (0, 1, 2) clear quorum (75 of 100) whether or
  // not index 0 is repeated, so this specifically isolates verifyAgg's own defense rather than
  // merely re-exercising the quorum-stake gate above. Confirms the M4 rejection is load-bearing for
  // this caller, not merely redundant with an upstream guarantee.
  it should "reject a QC whose signerIndexes contains a repeated signer (wire-crafted duplicate)" in {
    val aggSig     = Seq(vote(0), vote(1), vote(2)).map(_.signature.arr).reduceLeft((a, b) => BlsUtils.aggSign(a, b).toOption.get)
    val duplicated = QuorumCertificate(view, phase, blockId, height, Seq(0, 0, 1, 2), ByteStr(aggSig))
    HotStuffQuorum.verifyQC(duplicated, committee, cryptoV2 = false).isLeft should be(true)
  }
}
