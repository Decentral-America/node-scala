package com.decentralchain.utils

import com.decentralchain.state.Height
import com.decentralchain.test.FreeSpec

/** Audit M2 offline-signer era derivation. `UtilApp.blsCryptoV2Era` must mirror
  * `Blockchain.supportsBlsCryptoV2`/`TransactionsApiRoute.mkTxFactory`: a CommitToGeneration tx
  * signed offline lands, at the earliest, at `currentHeight + 1`, and an explicit CLI override
  * (`--bls-crypto-v2-activation-height`) always wins over the settings-derived activation height.
  */
class UtilAppSpec extends FreeSpec {
  "blsCryptoV2Era" - {
    "settings-derived activation height" - {
      "false while currentHeight + 1 is below the activation height" in {
        UtilApp.blsCryptoV2Era(Height(3), None, Some(Height(5))) shouldBe false
      }

      "true once currentHeight + 1 reaches the activation height" in {
        UtilApp.blsCryptoV2Era(Height(4), None, Some(Height(5))) shouldBe true
      }

      "true once currentHeight + 1 is past the activation height" in {
        UtilApp.blsCryptoV2Era(Height(10), None, Some(Height(5))) shouldBe true
      }

      "false when no activation height is known at all" in {
        UtilApp.blsCryptoV2Era(Height(100), None, None) shouldBe false
      }
    }

    "explicit CLI override" - {
      "wins over the settings-derived activation height when both are present" in {
        // settings says already active from height 1, override says not until height 50 -- override
        // must win, even though the settings-derived answer alone would be `true` here.
        UtilApp.blsCryptoV2Era(Height(47), Some(Height(50)), Some(Height(1))) shouldBe false
        UtilApp.blsCryptoV2Era(Height(48), Some(Height(50)), Some(Height(1))) shouldBe false
        UtilApp.blsCryptoV2Era(Height(49), Some(Height(50)), Some(Height(1))) shouldBe true
      }

      "lets a VOTE-activated chain (no preActivatedFeatures entry) be signed for offline" in {
        UtilApp.blsCryptoV2Era(Height(198), Some(Height(200)), None) shouldBe false
        UtilApp.blsCryptoV2Era(Height(199), Some(Height(200)), None) shouldBe true
      }
    }
  }
}
