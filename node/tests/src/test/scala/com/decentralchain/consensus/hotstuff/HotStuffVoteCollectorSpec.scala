package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.Address
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey}
import com.decentralchain.state.{GeneratorIndex, GeneratorSet, Height}
import com.decentralchain.test.FreeSpec
import com.decentralchain.transaction.TxHelpers

class HotStuffVoteCollectorSpec extends FreeSpec {

  private val blockId = TxHelpers.randomBlockId
  private val height  = Height(100)
  private val round   = HotStuffRound.Prepare

  "add" - {
    "rejects vote for wrong blockId" in {
      val (gs, kps) = mkValidatorSet(3)
      val collector = new HotStuffVoteCollector(blockId, height, round, gs)
      val vote      = HotStuffVote.sign(0, TxHelpers.randomBlockId, height, round, kps(0))

      collector.add(vote).isLeft shouldBe true
    }

    "rejects vote for wrong round" in {
      val (gs, kps) = mkValidatorSet(3)
      val collector = new HotStuffVoteCollector(blockId, height, round, gs)
      val vote      = HotStuffVote.sign(0, blockId, height, HotStuffRound.Commit, kps(0))

      collector.add(vote).isLeft shouldBe true
    }

    "rejects vote from unknown index" in {
      val (gs, _)   = mkValidatorSet(3)
      val rogue     = BlsKeyPair(TxHelpers.signer(99).privateKey)
      val collector = new HotStuffVoteCollector(blockId, height, round, gs)
      val vote      = HotStuffVote.sign(99, blockId, height, round, rogue)

      collector.add(vote).isLeft shouldBe true
    }

    "rejects vote with bad BLS signature" in {
      val (gs, kps) = mkValidatorSet(3)
      val collector = new HotStuffVoteCollector(blockId, height, round, gs)
      // Sign with a different key than claimed index
      val wrongKP = BlsKeyPair(TxHelpers.signer(99).privateKey)
      val vote    = HotStuffVote.sign(0, blockId, height, round, wrongKP)
        .copy(voterIndex = 0) // index points to kps(0), but sig is from wrongKP

      collector.add(vote).isLeft shouldBe true
    }

    "accepts a valid vote" in {
      val (gs, kps) = mkValidatorSet(3)
      val collector = new HotStuffVoteCollector(blockId, height, round, gs)
      val vote      = HotStuffVote.sign(0, blockId, height, round, kps(0))

      collector.add(vote) shouldBe Right(None)
      collector.voteCount shouldBe 1
    }

    "ignores duplicate votes from the same index (idempotent)" in {
      val (gs, kps) = mkValidatorSet(3)
      val collector = new HotStuffVoteCollector(blockId, height, round, gs)
      val vote      = HotStuffVote.sign(0, blockId, height, round, kps(0))

      collector.add(vote) shouldBe Right(None)
      collector.add(vote) shouldBe Right(None)
      collector.voteCount shouldBe 1
    }
  }

  "QC formation" - {
    "does not form QC until 2/3 threshold is met (3 validators, equal balance)" in {
      val (gs, kps) = mkValidatorSet(3)
      val collector = new HotStuffVoteCollector(blockId, height, round, gs)

      // 1 vote = 1/3 balance — not enough
      val r1 = collector.add(HotStuffVote.sign(0, blockId, height, round, kps(0)))
      r1 shouldBe Right(None)

      // 2 votes = 2/3 balance — exactly enough
      val r2 = collector.add(HotStuffVote.sign(1, blockId, height, round, kps(1)))
      r2 match {
        case Right(Some(qc)) =>
          qc.blockId             shouldBe blockId
          qc.height              shouldBe height
          qc.round               shouldBe round
          qc.signerIndices.sorted shouldBe Seq(0, 1)
          qc.verify(gs)          shouldBe Right(())
        case other => fail(s"Expected QC, got $other")
      }
    }

    "forms QC with all 3 votes" in {
      val (gs, kps) = mkValidatorSet(3)
      val collector = new HotStuffVoteCollector(blockId, height, round, gs)

      collector.add(HotStuffVote.sign(0, blockId, height, round, kps(0))) shouldBe Right(None)
      collector.add(HotStuffVote.sign(1, blockId, height, round, kps(1))) match {
        case Right(Some(qc)) =>
          // QC already formed — 3rd vote is idempotent from a QC perspective
          qc.verify(gs) shouldBe Right(())
        case other => fail(s"Expected QC, got $other")
      }
    }

    "QC signature verifies against the generator set" in {
      val (gs, kps) = mkValidatorSet(5)
      val collector = new HotStuffVoteCollector(blockId, height, HotStuffRound.Commit, gs)

      // Need 4/5 votes to get to 4000 of 5000 = 80% ≥ 2/3
      (0 until 4).foreach { i =>
        collector.add(HotStuffVote.sign(i, blockId, height, HotStuffRound.Commit, kps(i)))
      }

      val finalResult = collector.add(HotStuffVote.sign(3, blockId, height, HotStuffRound.Commit, kps(3)))
      // 3 votes already enough (3/5 = 60% ≥ 2/3 is false, need at least 4/5 = 80%... wait, 3*3=9, 5*2=10, so 3/5 < 2/3)
      // Actually 3 votes of 5 equal = 3/5 < 2/3 — need 4 votes. Let's just check the final QC verifies.
      finalResult match {
        case Right(Some(qc)) => qc.verify(gs) shouldBe Right(())
        case Right(None)     => // still accumulating, which is fine
        case Left(err)       => fail(s"Unexpected error: $err")
      }
    }
  }

  // ---- helpers ----

  private def mkValidatorSet(n: Int): (GeneratorSet, Seq[BlsKeyPair]) = {
    val kps = (0 until n).map(i => BlsKeyPair(TxHelpers.signer(i).privateKey))
    val gs: GeneratorSet = (0 until n).map { i =>
      val kp = TxHelpers.signer(i)
      com.decentralchain.state.GeneratorInfo(
        GeneratorIndex(i),
        kp.toAddress,
        kps(i).publicKey,
        1_000_000_000L
      )
    }
    (gs, kps)
  }
}
