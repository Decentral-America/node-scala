package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.Address
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey, BlsSignature}
import com.decentralchain.state.{GeneratorIndex, GeneratorSet, Height}
import com.decentralchain.test.FreeSpec
import com.decentralchain.transaction.TxHelpers

class HotStuffQCSpec extends FreeSpec {

  "HotStuffVote" - {
    "signing message is deterministic" in {
      val blockId = TxHelpers.randomBlockId
      val height  = Height(42)
      val round   = HotStuffRound.Prepare

      val m1 = HotStuffVote.signingMessage(blockId, height, round)
      val m2 = HotStuffVote.signingMessage(blockId, height, round)
      m1 shouldBe m2
    }

    "signing messages differ across rounds" in {
      val blockId = TxHelpers.randomBlockId
      val height  = Height(42)

      val mp = HotStuffVote.signingMessage(blockId, height, HotStuffRound.Prepare)
      val mc = HotStuffVote.signingMessage(blockId, height, HotStuffRound.PreCommit)
      val mk = HotStuffVote.signingMessage(blockId, height, HotStuffRound.Commit)

      mp should not equal mc
      mc should not equal mk
    }

    "verifySignature succeeds with matching key" in {
      val kp      = TxHelpers.signer(0)
      val blsKP   = BlsKeyPair(kp.privateKey)
      val blockId = TxHelpers.randomBlockId
      val vote    = HotStuffVote.sign(0, blockId, Height(10), HotStuffRound.Prepare, blsKP)

      vote.verifySignature(blsKP.publicKey) shouldBe Right(())
    }

    "verifySignature fails with wrong key" in {
      val kp1   = TxHelpers.signer(0)
      val kp2   = TxHelpers.signer(1)
      val vote  = HotStuffVote.sign(0, TxHelpers.randomBlockId, Height(10), HotStuffRound.Prepare, BlsKeyPair(kp1.privateKey))

      vote.verifySignature(BlsKeyPair(kp2.privateKey).publicKey).isLeft shouldBe true
    }
  }

  "HotStuffQC" - {
    "verify succeeds for a valid single-signer QC" in {
      val kp      = TxHelpers.signer(0)
      val blsKP   = BlsKeyPair(kp.privateKey)
      val blockId = TxHelpers.randomBlockId
      val height  = Height(99)
      val round   = HotStuffRound.PreCommit

      val vote = HotStuffVote.sign(0, blockId, height, round, blsKP)
      val qc   = HotStuffQC(blockId, height, round, Seq(0), vote.signature)
      val gs   = mkGeneratorSet(Seq((0, kp.toAddress, blsKP.publicKey, 1_000_000_000L)))

      qc.verify(gs) shouldBe Right(())
    }

    "verify fails when signature does not match the signerIndices" in {
      val kp1     = TxHelpers.signer(0)
      val kp2     = TxHelpers.signer(1)
      val blsKP2  = BlsKeyPair(kp2.privateKey)
      val blockId = TxHelpers.randomBlockId
      val height  = Height(99)
      val round   = HotStuffRound.Prepare

      // Signature is from kp2 but QC claims it's from index 0 (kp1)
      val vote = HotStuffVote.sign(1, blockId, height, round, blsKP2)
      val qc   = HotStuffQC(blockId, height, round, Seq(0), vote.signature)
      val gs   = mkGeneratorSet(Seq(
        (0, kp1.toAddress, BlsKeyPair(kp1.privateKey).publicKey, 1_000_000_000L),
        (1, kp2.toAddress, blsKP2.publicKey, 1_000_000_000L)
      ))

      qc.verify(gs).isLeft shouldBe true
    }

    "verify fails when signerIndex does not exist in generatorSet" in {
      val kp      = TxHelpers.signer(0)
      val blsKP   = BlsKeyPair(kp.privateKey)
      val blockId = TxHelpers.randomBlockId
      val vote    = HotStuffVote.sign(0, blockId, Height(1), HotStuffRound.Commit, blsKP)
      val qc      = HotStuffQC(blockId, Height(1), HotStuffRound.Commit, Seq(99), vote.signature)
      val gs      = mkGeneratorSet(Seq((0, kp.toAddress, blsKP.publicKey, 1_000_000_000L)))

      qc.verify(gs).isLeft shouldBe true
    }

    "meetsThreshold requires exactly 2/3 of balance" in {
      val kps = (0 until 3).map(i => (i, TxHelpers.signer(i))).map { case (i, kp) =>
        (i, kp.toAddress, BlsKeyPair(kp.privateKey).publicKey, 1_000L)
      }
      val gs      = mkGeneratorSet(kps)
      val blockId = TxHelpers.randomBlockId

      // 1/3 signers — not enough
      val qc1 = HotStuffQC(blockId, Height(1), HotStuffRound.Prepare, Seq(0), dummySig)
      qc1.meetsThreshold(gs) shouldBe false

      // 2/3 signers — exactly enough
      val qc2 = HotStuffQC(blockId, Height(1), HotStuffRound.Prepare, Seq(0, 1), dummySig)
      qc2.meetsThreshold(gs) shouldBe true

      // all 3 signers
      val qc3 = HotStuffQC(blockId, Height(1), HotStuffRound.Prepare, Seq(0, 1, 2), dummySig)
      qc3.meetsThreshold(gs) shouldBe true
    }
  }

  "HotStuffRound" - {
    "round sequence is Prepare → PreCommit → Commit → None" in {
      HotStuffRound.Prepare.next   shouldBe Some(HotStuffRound.PreCommit)
      HotStuffRound.PreCommit.next shouldBe Some(HotStuffRound.Commit)
      HotStuffRound.Commit.next    shouldBe None
    }

    "fromCode round-trips all values" in {
      HotStuffRound.all.foreach { r =>
        HotStuffRound.fromCode(r.code) shouldBe Some(r)
      }
    }

    "fromCode returns None for unknown code" in {
      HotStuffRound.fromCode(42.toByte) shouldBe None
    }
  }

  // ---- helpers ----

  private def mkGeneratorSet(entries: Seq[(Int, Address, BlsPublicKey, Long)]): GeneratorSet =
    entries.map { case (idx, addr, pk, bal) =>
      com.decentralchain.state.GeneratorInfo(GeneratorIndex(idx), addr, pk, bal)
    }

  private val dummySig: BlsSignature = {
    val kp = TxHelpers.signer(99)
    val blsKP = BlsKeyPair(kp.privateKey)
    blsKP.sign("dummy".getBytes("UTF-8"))
  }
}
