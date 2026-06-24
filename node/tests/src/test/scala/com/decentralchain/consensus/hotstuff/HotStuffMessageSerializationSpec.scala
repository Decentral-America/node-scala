package com.decentralchain.consensus.hotstuff

import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsSignature}
import com.decentralchain.network.{HotStuffQCMessage, HotStuffQCSpec, HotStuffVoteMessage, HotStuffVoteSpec}
import com.decentralchain.state.Height
import com.decentralchain.test.FreeSpec
import com.decentralchain.transaction.TxHelpers

import java.util.concurrent.ThreadLocalRandom

class HotStuffMessageSerializationSpec extends FreeSpec {

  private val blsKP   = BlsKeyPair(TxHelpers.signer(0).privateKey)
  private val sig     = blsKP.sign("test".getBytes("UTF-8"))

  "HotStuffVoteMessage" - {
    "roundtrips with a 64-byte (legacy) blockId" in {
      val msg = HotStuffVoteMessage(
        voterIndex = 3,
        blockId    = TxHelpers.randomBlockId, // 64 bytes
        height     = Height(999),
        round      = HotStuffRound.PreCommit,
        signature  = sig
      )
      val bytes       = HotStuffVoteSpec.serializeData(msg)
      val roundTripped = HotStuffVoteSpec.deserializeData(bytes).get

      roundTripped.voterIndex shouldBe msg.voterIndex
      roundTripped.blockId    shouldBe msg.blockId
      roundTripped.height     shouldBe msg.height
      roundTripped.round      shouldBe msg.round
      roundTripped.signature  shouldBe msg.signature
    }

    "roundtrips with a 32-byte (proto) blockId" in {
      val blockId32 = ByteStr(Array.fill(32)(ThreadLocalRandom.current().nextInt(256).toByte))
      val msg = HotStuffVoteMessage(
        voterIndex = 0,
        blockId    = blockId32,
        height     = Height(1),
        round      = HotStuffRound.Commit,
        signature  = sig
      )
      val bytes        = HotStuffVoteSpec.serializeData(msg)
      val roundTripped = HotStuffVoteSpec.deserializeData(bytes).get

      roundTripped.voterIndex shouldBe msg.voterIndex
      roundTripped.blockId    shouldBe msg.blockId
      roundTripped.round      shouldBe msg.round
    }

    "roundtrips all three rounds" in {
      HotStuffRound.all.foreach { round =>
        val msg = HotStuffVoteMessage(0, TxHelpers.randomBlockId, Height(1), round, sig)
        val bytes = HotStuffVoteSpec.serializeData(msg)
        HotStuffVoteSpec.deserializeData(bytes).get.round shouldBe round
      }
    }

    "serialized size is within maxLength" in {
      val msg   = HotStuffVoteMessage(0, TxHelpers.randomBlockId, Height(1), HotStuffRound.Prepare, sig)
      val bytes = HotStuffVoteSpec.serializeData(msg)
      bytes.length should be <= HotStuffVoteSpec.maxLength
    }

    "rejects bytes with invalid blockId length" in {
      // Construct bytes with blockIdLen = 16 (neither 32 nor 64)
      val bad = Array[Byte](16.toByte) ++ Array.fill(16)(0: Byte) ++ Array.fill(4 + 1 + 96)(0: Byte)
      HotStuffVoteSpec.deserializeData(bad).isFailure shouldBe true
    }

    "rejects bytes that are too short" in {
      HotStuffVoteSpec.deserializeData(Array.fill(10)(0: Byte)).isFailure shouldBe true
    }
  }

  "HotStuffQCMessage" - {
    "roundtrips with zero signers" in {
      // A zero-signer QC is degenerate but must not crash serialization
      val msg = HotStuffQCMessage(
        blockId              = TxHelpers.randomBlockId,
        height               = Height(42),
        round                = HotStuffRound.Commit,
        signerIndices        = Seq.empty,
        aggregatedSignature  = sig
      )
      val bytes        = HotStuffQCSpec.serializeData(msg)
      val roundTripped = HotStuffQCSpec.deserializeData(bytes).get

      roundTripped.blockId             shouldBe msg.blockId
      roundTripped.height              shouldBe msg.height
      roundTripped.round               shouldBe msg.round
      roundTripped.signerIndices       shouldBe empty
      roundTripped.aggregatedSignature shouldBe msg.aggregatedSignature
    }

    "roundtrips with multiple signers" in {
      val indices = Seq(0, 2, 5, 17, 49)
      val msg = HotStuffQCMessage(
        blockId             = TxHelpers.randomBlockId,
        height              = Height(1000),
        round               = HotStuffRound.PreCommit,
        signerIndices       = indices,
        aggregatedSignature = sig
      )
      val bytes        = HotStuffQCSpec.serializeData(msg)
      val roundTripped = HotStuffQCSpec.deserializeData(bytes).get

      roundTripped.signerIndices shouldBe indices
      roundTripped.height        shouldBe msg.height
      roundTripped.round         shouldBe msg.round
    }

    "roundtrips with a 32-byte blockId" in {
      val blockId32 = ByteStr(Array.fill(32)(ThreadLocalRandom.current().nextInt(256).toByte))
      val msg       = HotStuffQCMessage(blockId32, Height(7), HotStuffRound.Prepare, Seq(1, 3), sig)
      val bytes     = HotStuffQCSpec.serializeData(msg)
      HotStuffQCSpec.deserializeData(bytes).get.blockId shouldBe blockId32
    }

    "serialized size is within maxLength for worst-case 100 validators and 64-byte blockId" in {
      val indices = (0 until 100).toSeq
      val msg     = HotStuffQCMessage(TxHelpers.randomBlockId, Height(1), HotStuffRound.Commit, indices, sig)
      val bytes   = HotStuffQCSpec.serializeData(msg)
      bytes.length should be <= HotStuffQCSpec.maxLength
    }

    "rejects signer count > 100" in {
      // Manually craft bytes with signerCount = 101
      val blockId  = TxHelpers.randomBlockId.arr
      val bad = Array[Byte](blockId.length.toByte) ++ blockId ++
        Array[Byte](0, 0, 0, 1) ++ // height
        Array[Byte](0) ++           // round = Prepare
        Array[Byte](0, 0, 0, 101.toByte) ++ // signerCount = 101
        Array.fill(101 * 4 + 96)(0: Byte)
      HotStuffQCSpec.deserializeData(bad).isFailure shouldBe true
    }

    "roundtrips all three rounds" in {
      HotStuffRound.all.foreach { round =>
        val msg   = HotStuffQCMessage(TxHelpers.randomBlockId, Height(1), round, Seq(0), sig)
        val bytes = HotStuffQCSpec.serializeData(msg)
        HotStuffQCSpec.deserializeData(bytes).get.round shouldBe round
      }
    }
  }

  "message codes are stable" in {
    HotStuffVoteSpec.messageCode shouldBe 39.toByte
    HotStuffQCSpec.messageCode   shouldBe 40.toByte
  }
}
