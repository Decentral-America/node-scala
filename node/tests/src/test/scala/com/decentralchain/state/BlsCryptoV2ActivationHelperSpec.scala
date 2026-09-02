package com.decentralchain.state

import com.decentralchain.db.WithDomain
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.test.DomainPresets
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.test.FreeSpec

/** `supportsBlsCryptoV2` must behave exactly like its two siblings (`supportsFinalizationVoting`,
  * `supportsHotStuffEquivocationEvidence`): an activation-height comparison against an EXPLICIT
  * height argument, so callers can ask about the containing block rather than the live tip.
  */
class BlsCryptoV2ActivationHelperSpec extends FreeSpec with WithDomain {
  private val activatesAt5 =
    DomainPresets.DeterministicFinality.setFeaturesHeight(BlockchainFeatures.BlsCryptoV2 -> 5)

  private val never = DomainPresets.DeterministicFinality

  "supportsBlsCryptoV2" - {
    "false below the activation height, true at and above it" in withDomain(activatesAt5) { d =>
      d.blockchain.supportsBlsCryptoV2(4) shouldBe false
      d.blockchain.supportsBlsCryptoV2(5) shouldBe true
      d.blockchain.supportsBlsCryptoV2(6) shouldBe true
    }

    "false at every height when the feature is absent" in withDomain(never) { d =>
      d.blockchain.supportsBlsCryptoV2(1) shouldBe false
      d.blockchain.supportsBlsCryptoV2(Int.MaxValue) shouldBe false
    }
  }
}
