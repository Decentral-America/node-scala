package com.decentralchain.features

import com.decentralchain.test.FlatSpec

class BlockchainFeaturesRegistrySpec extends FlatSpec {
  "BlockchainFeatures" should "not have resurrected the burned feature id 28" in {
    BlockchainFeatures.feature(28) shouldBe None
    BlockchainFeatures.implemented should not contain 28.toShort
  }

  it should "have no DCC-native feature ids" in {
    BlockchainFeatures.feature(29) shouldBe None
    BlockchainFeatures.feature(30) shouldBe None
    BlockchainFeatures.implemented.max should be <= 28.toShort
  }

  // [rev.4] pins the safety net D1 depends on and D6 (removing the gates) partially trades away:
  // every registered id must have real logic wired in.
  it should "have implemented exactly the dict, and every registered feature is real" in {
    BlockchainFeatures.implemented shouldBe BlockchainFeatures.dict.keySet
  }
}
