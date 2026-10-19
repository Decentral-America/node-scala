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

  "verifyVote (gossip path)" should "reject a legacy-tagged vote signature" in {
    HotStuffQuorum.verifyVote(vote(0, BlsUtils.BlsLegacyDomainSeparationTag), committee) should be(false)
  }

  it should "still accept a current v2-tagged (_HSVOTE_) vote signature (no regression)" in {
    HotStuffQuorum.verifyVote(vote(0, BlsUtils.BlsHsVoteDomainSeparationTag), committee) should be(true)
  }

  it should "reject a vote signed under neither DST (wrong DST entirely)" in {
    HotStuffQuorum.verifyVote(vote(0, BlsUtils.BlsPopDomainSeparationTag), committee) should be(false)
  }

  "verifyVoteSignature (block-validation path)" should "accept legacy only when allowed, and count the acceptance" in {
    val v      = vote(0, BlsUtils.BlsLegacyDomainSeparationTag)
    val before = BlsUtils.legacyDstAcceptances.getOrElse("hotstuff-vote", 0L)
    HotStuffQuorum.verifyVoteSignature(v.signature.arr, voteMsg, kps(0).publicKey.arr, allowLegacyDst = true) should be(true)
    HotStuffQuorum.verifyVoteSignature(v.signature.arr, voteMsg, kps(0).publicKey.arr, allowLegacyDst = false) should be(false)
    BlsUtils.legacyDstAcceptances.getOrElse("hotstuff-vote", 0L) shouldBe before + 1
  }

  "verifyQC (gossip path)" should "reject a QC whose signatures are legacy-tagged" in {
    val votes = Seq(0, 1, 2).map(i => vote(i, BlsUtils.BlsLegacyDomainSeparationTag))
    val agg   = com.decentralchain.crypto.bls.BlsSignature
      .agg(votes.map(v => com.decentralchain.crypto.bls.BlsSignature(v.signature).toOption.get))
      .toOption
      .get
    val qc = QuorumCertificate(view, phase, blockId, height, Seq(0, 1, 2), agg.byteStr, 0)
    HotStuffQuorum.verifyQC(qc, committee).isLeft should be(true)
  }

  it should "still accept a QC whose signatures are v2 (_HSVOTE_)-tagged (no regression)" in {
    val votes = Seq(
      vote(0, BlsUtils.BlsHsVoteDomainSeparationTag),
      vote(1, BlsUtils.BlsHsVoteDomainSeparationTag),
      vote(2, BlsUtils.BlsHsVoteDomainSeparationTag)
    )
    val qc = HotStuffQuorum.formQC(votes, committee).toOption.get
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
