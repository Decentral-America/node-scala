package com.decentralchain.features

import com.decentralchain.test.FlatSpec

class BlockchainFeaturesRegistrySpec extends FlatSpec {
  "BlockchainFeatures" should "register HotStuff Equivocation Evidence as feature 29" in {
    BlockchainFeatures.feature(29) shouldBe Some(BlockchainFeatures.HotStuffEquivocationEvidence)
    BlockchainFeatures.implemented should contain(29.toShort)
  }
}
