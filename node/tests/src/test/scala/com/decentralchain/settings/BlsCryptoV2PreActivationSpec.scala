package com.decentralchain.settings

import com.decentralchain.test.FlatSpec

class BlsCryptoV2PreActivationSpec extends FlatSpec {
  "MAINNET" should "pre-activate feature 30 (BlsCryptoV2) from genesis" in {
    FunctionalitySettings.MAINNET.preActivatedFeatures.get(30.toShort) shouldBe Some(1)
  }

  "STAGENET" should "pre-activate feature 30 (BlsCryptoV2) from genesis" in {
    FunctionalitySettings.STAGENET.preActivatedFeatures.get(30.toShort) shouldBe Some(1)
  }

  "TESTNET" should "leave feature 30 (BlsCryptoV2) unactivated, to be voted in live" in {
    FunctionalitySettings.TESTNET.preActivatedFeatures.get(30.toShort) shouldBe None
  }
}
