package com.decentralchain.consensus.hotstuff

import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.Height
import io.decentralchain.protobuf.block.HotStuffPhase
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class HotStuffEquivocationProofSpecification extends AnyFreeSpec with Matchers {

  // Real BLS keypair so signaturesValid is tested for real, not mocked. Follows the same
  // seed-based construction as HotStuffQuorumSpecification.
  private val kp: BlsKeyPair = TestBlsKeyPair.unsafe(Array.fill[Byte](32)(1))

  private def signedVote(voter: Int, view: Int, phase: HotStuffPhase, blockIdByte: Byte, epoch: Int, keyPair: BlsKeyPair): HotStuffVote = {
    val blockId = ByteStr(Array.fill(32)(blockIdByte))
    val height  = Height(10)
    val msg     = HotStuffQuorum.voteMessage(view, phase, blockId, height.toInt, epoch)
    HotStuffVote(view, phase, blockId, height, voter, ByteStr(keyPair.sign(msg, BlsUtils.BlsDomainSeparationTag).arr), epoch)
  }

  private val prepare = HotStuffPhase.HOTSTUFF_PHASE_PREPARE

  "consistent" - {
    "accepts two votes: same voter/view/phase/epoch, different blockIds" in {
      val p = HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, prepare, 2, 2, kp))
      p.consistent shouldBe Right(())
      p.voterIndex shouldBe 0; p.view shouldBe 5; p.committeeEpoch shouldBe 2
    }
    "rejects same blockId (not an equivocation)" in {
      HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, prepare, 1, 2, kp)).consistent.isLeft shouldBe true
    }
    "rejects different voters" in {
      HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(1, 5, prepare, 2, 2, kp)).consistent.isLeft shouldBe true
    }
    "rejects different views" in {
      HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 6, prepare, 2, 2, kp)).consistent.isLeft shouldBe true
    }
    "rejects different phases" in {
      HotStuffEquivocationProof(
        signedVote(0, 5, prepare, 1, 2, kp),
        signedVote(0, 5, HotStuffPhase.HOTSTUFF_PHASE_COMMIT, 2, 2, kp)
      ).consistent.isLeft shouldBe true
    }
    "rejects CROSS-EPOCH pairs (same index may be a different generator)" in {
      HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, prepare, 2, 3, kp)).consistent.isLeft shouldBe true
    }
    "rejects UNSPECIFIED phase" in {
      val u = HotStuffPhase.HOTSTUFF_PHASE_UNSPECIFIED
      HotStuffEquivocationProof(signedVote(0, 5, u, 1, 2, kp), signedVote(0, 5, u, 2, 2, kp)).consistent.isLeft shouldBe true
    }
  }

  "signaturesValid" - {
    "accepts when both votes verify against the voter's real key" in {
      val p = HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, prepare, 2, 2, kp))
      p.signaturesValid(_ => Some(kp.publicKey), BlsUtils.BlsDomainSeparationTag) shouldBe Right(())
    }
    "rejects a forged voteB (an attacker cannot frame an honest voter)" in {
      val forged = signedVote(0, 5, prepare, 2, 2, kp).copy(signature = ByteStr(Array.fill(96)(7: Byte)))
      HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), forged)
        .signaturesValid(_ => Some(kp.publicKey), BlsUtils.BlsDomainSeparationTag)
        .isLeft shouldBe true
    }
    "rejects when the index is outside the committee" in {
      val p = HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, prepare, 2, 2, kp))
      p.signaturesValid(_ => None, BlsUtils.BlsDomainSeparationTag).isLeft shouldBe true
    }
    "rejects when signed under the wrong DST" in {
      val p = HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, prepare, 2, 2, kp))
      p.signaturesValid(_ => Some(kp.publicKey), BlsUtils.BlsHsVoteDomainSeparationTagV2).isLeft shouldBe true
    }
  }
}
