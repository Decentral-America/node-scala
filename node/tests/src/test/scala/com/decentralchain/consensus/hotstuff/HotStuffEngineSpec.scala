package com.decentralchain.consensus.hotstuff

import com.decentralchain.crypto.bls.{BlsKeyPair, BlsSignature}
import com.decentralchain.settings.HotStuffSettings
import com.decentralchain.state.{GeneratorIndex, GeneratorSet, Height}
import com.decentralchain.test.FreeSpec
import com.decentralchain.transaction.TxHelpers
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.duration.*

/** Tests the HotStuff engine using a real ActorSystem and an observable HotStuffFinalityTracker.
  * No TestKit: we observe consensus outcomes through the tracker, not through actor probes.
  */
class HotStuffEngineSpec extends FreeSpec with BeforeAndAfterAll {

  private val system      = ActorSystem("HotStuffEngineSpec")
  private val allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
  private val settings    = HotStuffSettings(enabled = true, roundTimeoutMs = 200L)

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }

  "HotStuffEngine" - {

    "goes idle when node is not in the validator set" in {
      val (gs, _, _, engine, tracker) = mkEngine(validatorIndex = 0, nValidators = 3)
      val gsWithoutMe                 = gs.tail // drop index 0 — remove the validator

      engine ! HotStuffEngine.BlockApplied(TxHelpers.randomBlockId, Height(1), TxHelpers.signer(1).toAddress, gsWithoutMe)

      Thread.sleep(80)
      tracker.finalizedHeight shouldBe None
    }

    "falls back to T0 after round timeout with no votes" in {
      val (gs, _, myAddress, engine, tracker) = mkEngine(validatorIndex = 0, nValidators = 3)

      engine ! HotStuffEngine.BlockApplied(TxHelpers.randomBlockId, Height(5), myAddress, gs)

      // Timeout fires after 200ms — no votes arrive.
      Thread.sleep(350)
      tracker.finalizedHeight shouldBe None
    }

    "leader path: records Commit QC after receiving 2/3 votes across all 3 rounds" in {
      val n           = 3
      val blsKPs      = (0 until n).map(i => BlsKeyPair(TxHelpers.signer(i).privateKey))
      val gs          = mkValidatorSet(n, blsKPs)
      val myIndex     = 0 // node 0 is both the leader (forger) and a validator
      val myAddress   = TxHelpers.signer(myIndex).toAddress
      val tracker     = new HotStuffFinalityTracker()
      val engine      = system.actorOf(HotStuffEngine.props(myAddress, blsKPs(myIndex), settings, allChannels, tracker))

      val blockId = TxHelpers.randomBlockId
      val height  = Height(20)

      engine ! HotStuffEngine.BlockApplied(blockId, height, myAddress, gs)
      Thread.sleep(30)

      // Validators 1 and 2 cast Prepare votes.
      Seq(1, 2).foreach { i =>
        engine ! HotStuffEngine.VoteReceived(HotStuffVote.sign(i, blockId, height, HotStuffRound.Prepare, blsKPs(i)))
      }
      Thread.sleep(30)

      // Validators 1 and 2 cast PreCommit votes.
      Seq(1, 2).foreach { i =>
        engine ! HotStuffEngine.VoteReceived(HotStuffVote.sign(i, blockId, height, HotStuffRound.PreCommit, blsKPs(i)))
      }
      Thread.sleep(30)

      // Validators 1 and 2 cast Commit votes.
      Seq(1, 2).foreach { i =>
        engine ! HotStuffEngine.VoteReceived(HotStuffVote.sign(i, blockId, height, HotStuffRound.Commit, blsKPs(i)))
      }
      Thread.sleep(100)

      tracker.finalizedHeight       shouldBe Some(height)
      tracker.latestFinalizedBlock.map(_.blockId) shouldBe Some(blockId)
    }

    "validator path: advances rounds on valid QCs and records commit finality" in {
      val n          = 3
      val blsKPs     = (0 until n).map(i => BlsKeyPair(TxHelpers.signer(i).privateKey))
      val gs         = mkValidatorSet(n, blsKPs)
      val myIndex    = 1 // not the leader (leader is node 0)
      val myAddress  = TxHelpers.signer(myIndex).toAddress
      val leaderAddr = TxHelpers.signer(0).toAddress
      val tracker    = new HotStuffFinalityTracker()
      val engine     = system.actorOf(HotStuffEngine.props(myAddress, blsKPs(myIndex), settings, allChannels, tracker))

      val blockId = TxHelpers.randomBlockId
      val height  = Height(30)

      engine ! HotStuffEngine.BlockApplied(blockId, height, leaderAddr, gs)
      Thread.sleep(20)

      // Simulate leader broadcasting Prepare QC (signed by nodes 0 and 2).
      engine ! HotStuffEngine.QCReceived(mkQC(blockId, height, HotStuffRound.Prepare, Seq(0, 2), blsKPs))
      Thread.sleep(20)

      // Simulate leader broadcasting PreCommit QC.
      engine ! HotStuffEngine.QCReceived(mkQC(blockId, height, HotStuffRound.PreCommit, Seq(0, 2), blsKPs))
      Thread.sleep(20)

      // Simulate leader broadcasting Commit QC.
      engine ! HotStuffEngine.QCReceived(mkQC(blockId, height, HotStuffRound.Commit, Seq(0, 2), blsKPs))
      Thread.sleep(100)

      tracker.finalizedHeight shouldBe Some(height)
    }

    "rejects a vote with a bad BLS signature (wrong key)" in {
      val n         = 3
      val blsKPs    = (0 until n).map(i => BlsKeyPair(TxHelpers.signer(i).privateKey))
      val gs        = mkValidatorSet(n, blsKPs)
      val myIndex   = 0
      val myAddress = TxHelpers.signer(myIndex).toAddress
      val tracker   = new HotStuffFinalityTracker()
      val engine    = system.actorOf(HotStuffEngine.props(myAddress, blsKPs(myIndex), settings, allChannels, tracker))

      val blockId = TxHelpers.randomBlockId
      val height  = Height(50)

      engine ! HotStuffEngine.BlockApplied(blockId, height, myAddress, gs)
      Thread.sleep(20)

      // Index 1 sends a vote but with the WRONG key (index 2's key) — BLS verification must fail.
      val wrongKP    = blsKPs(2)
      val invalidVote = HotStuffVote.sign(1, blockId, height, HotStuffRound.Prepare, wrongKP)
      engine ! HotStuffEngine.VoteReceived(invalidVote)
      Thread.sleep(100)

      // No finality — bad vote doesn't count.
      tracker.finalizedHeight shouldBe None
    }

    "new block preempts in-progress round" in {
      val n         = 3
      val blsKPs    = (0 until n).map(i => BlsKeyPair(TxHelpers.signer(i).privateKey))
      val gs        = mkValidatorSet(n, blsKPs)
      val myAddress = TxHelpers.signer(0).toAddress
      val tracker   = new HotStuffFinalityTracker()
      val engine    = system.actorOf(HotStuffEngine.props(myAddress, blsKPs(0), settings, allChannels, tracker))

      val blockId1 = TxHelpers.randomBlockId
      val blockId2 = TxHelpers.randomBlockId
      val h1       = Height(60)
      val h2       = Height(61)

      engine ! HotStuffEngine.BlockApplied(blockId1, h1, myAddress, gs)
      Thread.sleep(20)

      // New block arrives before round for h1 completes — cancels h1 round.
      engine ! HotStuffEngine.BlockApplied(blockId2, h2, myAddress, gs)
      Thread.sleep(20)

      // Vote for h1 now — must be ignored (wrong blockId).
      Seq(1, 2).foreach { i =>
        engine ! HotStuffEngine.VoteReceived(HotStuffVote.sign(i, blockId1, h1, HotStuffRound.Prepare, blsKPs(i)))
      }
      Thread.sleep(100)

      // Only h2 can finalize now; h1 is abandoned.
      tracker.finalizedHeight should not be Some(h1)
    }
  }

  // ---- helpers ----

  private def mkEngine(
      validatorIndex: Int,
      nValidators: Int
  ): (GeneratorSet, BlsKeyPair, com.decentralchain.account.Address, ActorRef, HotStuffFinalityTracker) = {
    val blsKPs    = (0 until nValidators).map(i => BlsKeyPair(TxHelpers.signer(i).privateKey))
    val gs        = mkValidatorSet(nValidators, blsKPs)
    val myKP      = blsKPs(validatorIndex)
    val myAddress = TxHelpers.signer(validatorIndex).toAddress
    val tracker   = new HotStuffFinalityTracker()
    val engine    = system.actorOf(HotStuffEngine.props(myAddress, myKP, settings, allChannels, tracker))
    (gs, myKP, myAddress, engine, tracker)
  }

  private def mkValidatorSet(n: Int, kps: Seq[BlsKeyPair]): GeneratorSet =
    (0 until n).map { i =>
      com.decentralchain.state.GeneratorInfo(
        GeneratorIndex(i),
        TxHelpers.signer(i).toAddress,
        kps(i).publicKey,
        1_000_000_000L
      )
    }

  /** Builds a real BLS-aggregated QC signed by the given signer indices. */
  private def mkQC(
      blockId: com.decentralchain.block.Block.BlockId,
      height: Height,
      round: HotStuffRound,
      signerIndices: Seq[Int],
      blsKPs: Seq[BlsKeyPair]
  ): HotStuffQC = {
    val votes  = signerIndices.map(i => HotStuffVote.sign(i, blockId, height, round, blsKPs(i)))
    val aggSig = BlsSignature.agg(votes.map(_.signature)).fold(e => fail(s"BLS agg failed: $e"), identity)
    HotStuffQC(blockId, height, round, signerIndices, aggSig)
  }
}
