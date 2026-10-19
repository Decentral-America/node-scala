package com.decentralchain.mining

import com.decentralchain.common.state.ByteStr
import com.decentralchain.state.StateSnapshot
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.transaction.TxValidationError.GenericError
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class MinerKeyBlockStateHashSpecification extends AnyFreeSpec with Matchers {
  private val miner = TxHelpers.signer(1).toAddress
  private val ref   = ByteStr(Array.fill[Byte](32)(4))
  private val prev  = ByteStr(Array.fill[Byte](32)(9))

  "a failed initial snapshot is an error naming the cause and the reference, not a block with no state hash" in {
    val r = Miner.keyBlockStateHash(Some(prev), Left(GenericError("Invalid penalized generator index 7, valid: [0; 4]")), miner, ref)
    r.isLeft shouldBe true
    r.left.toOption.get should include("Invalid penalized generator index 7")
    r.left.toOption.get should include(ref.toString)
  }

  "an empty initial snapshot keeps the previous hash" in {
    Miner.keyBlockStateHash(Some(prev), Right(StateSnapshot.empty), miner, ref) shouldBe Right(Some(prev))
  }

  "with light-node fields inactive (no previous hash) the snapshot is not even evaluated" in {
    Miner.keyBlockStateHash(None, throw new AssertionError("must not be evaluated"), miner, ref) shouldBe Right(None)
  }
}
