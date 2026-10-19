package com.decentralchain.settings

import com.typesafe.config.ConfigFactory
import com.decentralchain.test.FlatSpec
import pureconfig.error.ConfigReaderException

class KnownChainParametersSpecification extends FlatSpec {
  private def dccTestnet(functionalityExtra: String): BlockchainSettings =
    BlockchainSettings.fromRootConfig(
      loadConfig(
        ConfigFactory.parseString(
          s"""dcc {
             |  directory = "/tmp/dcc-known-chain-spec"
             |  blockchain {
             |    type = CUSTOM
             |    custom {
             |      address-scheme-character = "!"
             |      functionality {
             |        generation-period-length = 100
             |        $functionalityExtra
             |      }
             |      rewards { term = 100000, term-after-capped-reward-feature = 50000, initial = 600000000, min-increment = 50000000, voting-interval = 10000 }
             |      genesis {
             |        average-block-delay = 30s
             |        initial-base-target = 218
             |        timestamp = 1788539579643
             |        block-timestamp = 1788539579643
             |        signature = "5be1Fs9oqWGnUt1vVpBDXaJNN583tqic72hFwY9mmMf56n5WevrmwqMzdLSRVdEu7DWGhuQkVL1zhYBn4iuz9DX4"
             |        initial-balance = 10000000000000000
             |        transactions = [{ recipient = "31VMNVAvVh67dPZ41nMnYZvhoZFW7wwPrmq", amount = 10000000000000000 }]
             |      }
             |    }
             |  }
             |}""".stripMargin
        )
      )
    )

  "the DCC testnet relaunch chain" should "get its pinned values when the config omits the keys (stale config)" in {
    val fs = dccTestnet("").functionalitySettings
    fs.hotStuffEquivocationPenaltyHeight shouldBe 60000
    fs.finalityHardeningHeight shouldBe 90000
  }

  it should "accept the pinned values when set explicitly" in {
    val fs = dccTestnet("hot-stuff-equivocation-penalty-height = 60000\n finality-hardening-height = 90000").functionalitySettings
    fs.hotStuffEquivocationPenaltyHeight shouldBe 60000
    fs.finalityHardeningHeight shouldBe 90000
  }

  it should "refuse to load a conflicting value" in {
    val e = the[ConfigReaderException[?]] thrownBy dccTestnet("hot-stuff-equivocation-penalty-height = 50000")
    e.getMessage should include("conflicts with the value 60000")
  }

  "an unknown chain" should "keep exactly what is configured (defaults = hardened from genesis)" in {
    KnownChainParameters.enforce(None, FunctionalitySettings()) shouldBe Right(FunctionalitySettings())
    FunctionalitySettings().finalityHardeningHeight shouldBe 0
  }
}
