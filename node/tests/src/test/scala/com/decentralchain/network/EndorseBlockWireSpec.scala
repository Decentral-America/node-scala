package com.decentralchain.network

import com.google.protobuf.ByteString
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.EndorseBlock as PBEndorseBlock

class EndorseBlockWireSpec extends FlatSpec {
  private def wire(index: Int): Array[Byte] =
    PBEndorseBlock(
      endorserIndex = index,
      finalizedBlockId = ByteString.copyFrom(Array.fill[Byte](32)(1)),
      finalizedBlockHeight = 5,
      endorsedBlockId = ByteString.copyFrom(Array.fill[Byte](32)(2)),
      signature = ByteString.copyFrom(Array.fill[Byte](96)(3))
    ).toByteArray

  "EndorseBlockSpec.deserializeData" should "reject a negative endorser index at decode time (MessageCodec then closes the channel)" in {
    EndorseBlockSpec.deserializeData(wire(-2)).isFailure shouldBe true
    EndorseBlockSpec.deserializeData(wire(-1)).isFailure shouldBe true
  }

  it should "decode a non-negative endorser index" in {
    EndorseBlockSpec.deserializeData(wire(3)).map(_.endorserIndex) shouldBe scala.util.Success(3)
  }
}
