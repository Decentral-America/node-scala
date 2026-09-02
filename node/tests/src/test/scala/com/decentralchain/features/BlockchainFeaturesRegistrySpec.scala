package com.decentralchain.features

import com.decentralchain.test.FlatSpec

class BlockchainFeaturesRegistrySpec extends FlatSpec {
  "BlockchainFeatures" should "register HotStuff Equivocation Evidence as feature 29" in {
    BlockchainFeatures.feature(29) shouldBe Some(BlockchainFeatures.HotStuffEquivocationEvidence)
    BlockchainFeatures.implemented should contain(29.toShort)
  }

  it should "register BLS domain separation & bound PoP as feature 30" in {
    BlockchainFeatures.feature(30) shouldBe Some(BlockchainFeatures.BlsCryptoV2)
    BlockchainFeatures.implemented should contain(30.toShort)
  }

  it should "not have resurrected the burned feature id 28" in {
    BlockchainFeatures.feature(28) shouldBe None
    BlockchainFeatures.implemented should not contain 28.toShort
  }
}
