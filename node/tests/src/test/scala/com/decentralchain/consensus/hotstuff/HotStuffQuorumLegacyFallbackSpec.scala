package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsSignature, BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffVote, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

/** Regression coverage for the verify-side backward-compatibility fix extending Task 8b's
  * `CommitToGenerationTransaction.verifyPop` fix to `HotStuffQuorum.verifyVote`/`verifyQC`.
  *
  * Commit `448d56557f` made the identical change here that it made to the PoP path: it deleted the
  * legacy `voteDst(cryptoV2: Boolean)` fallback (legacy `_NUL_` DST pre-activation, `_HSVOTE_` DST
  * post-activation) in favor of an unconditional `VoteDst = BlsHsVoteDomainSeparationTag`, on the
  * claim that "this chain never had a prior HotStuff-vote DST to stay compatible with." A real-chain
  * replay of the testnet-relaunch chain (genesis 2026-08-31) against the fixed PoP-only build stalled
  * deterministically at height ~507 on a block carrying a `Voting` record with `Wrong BLS signature`
  * -- `voteMessage`'s layout was never changed by `448d56557f`, only the DST selection was (same
  * pattern as PoP), so `verifyVote`/`verifyQC` now also try the legacy `_NUL_` DST as a fallback.
  * New vote signing (`HotStuffCoordinator.castVotes`) stays v2-only.
  */
class HotStuffQuorumLegacyFallbackSpec extends FlatSpec {
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

  private def voteMsg: Array[Byte] = HotStuffQuorum.voteMessage(view, phase, blockId, height.toInt)

  private def vote(i: Int, dst: String): HotStuffVote =
    HotStuffVote(view, phase, blockId, height, i, kps(i).sign(voteMsg, dst).byteStr)

  "verifyVote" should "accept a legacy-tagged vote signature (pre-v2 on-chain vote)" in {
    val legacyVote = vote(0, BlsUtils.BlsLegacyDomainSeparationTag)
    HotStuffQuorum.verifyVote(legacyVote, committee) should be(true)
  }

  it should "still accept a current v2-tagged (_HSVOTE_) vote signature (no regression)" in {
    val v2Vote = vote(0, BlsUtils.BlsHsVoteDomainSeparationTag)
    HotStuffQuorum.verifyVote(v2Vote, committee) should be(true)
  }

  it should "reject a vote signed under neither DST (wrong DST entirely)" in {
    val wrongVote = vote(0, BlsUtils.BlsPopDomainSeparationTag)
    HotStuffQuorum.verifyVote(wrongVote, committee) should be(false)
  }

  it should "reject a legacy-tagged signature from the wrong voter (tampered voterIndex)" in {
    val legacyVote = vote(0, BlsUtils.BlsLegacyDomainSeparationTag)
    HotStuffQuorum.verifyVote(legacyVote.copy(voterIndex = 1), committee) should be(false)
  }

  "verifyQC" should "accept a QC whose signatures are legacy-tagged" in {
    val votes = Seq(vote(0, BlsUtils.BlsLegacyDomainSeparationTag), vote(1, BlsUtils.BlsLegacyDomainSeparationTag), vote(2, BlsUtils.BlsLegacyDomainSeparationTag))
    val qc    = HotStuffQuorum.formQC(votes, committee).toOption.get
    HotStuffQuorum.verifyQC(qc, committee) should be(Right(()))
  }

  it should "still accept a QC whose signatures are v2 (_HSVOTE_)-tagged (no regression)" in {
    val votes = Seq(vote(0, BlsUtils.BlsHsVoteDomainSeparationTag), vote(1, BlsUtils.BlsHsVoteDomainSeparationTag), vote(2, BlsUtils.BlsHsVoteDomainSeparationTag))
    val qc    = HotStuffQuorum.formQC(votes, committee).toOption.get
    HotStuffQuorum.verifyQC(qc, committee) should be(Right(()))
  }

  it should "reject a QC whose aggregated signature is wrong under both schemes" in {
    // Aggregate of PoP-DST signatures over the vote message -- valid under neither VoteDst nor the
    // legacy DST, since the message is right but the domain is neither of the two tried.
    val wrongDstSigs: Seq[BlsSignature] = Seq(0, 1, 2).map(i => kps(i).sign(voteMsg, BlsUtils.BlsPopDomainSeparationTag))
    val aggSig                          = BlsSignature.agg(wrongDstSigs).toOption.get
    val qc                              = QuorumCertificate(view, phase, blockId, height, Seq(0, 1, 2), aggSig.byteStr, 0)
    HotStuffQuorum.verifyQC(qc, committee).isLeft should be(true)
  }
}
