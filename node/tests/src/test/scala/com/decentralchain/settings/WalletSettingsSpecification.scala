package com.decentralchain.settings

import com.typesafe.config.ConfigFactory
import com.decentralchain.common.state.ByteStr
import com.decentralchain.test.FlatSpec
import pureconfig.ConfigSource

class WalletSettingsSpecification extends FlatSpec {
  "WalletSettings" should "read values from config" in {
    val config = loadConfig(ConfigFactory.parseString("""waves.wallet {
                                                        |  password: "some string as password"
                                                        |  seed: "BASE58SEED"
                                                        |}""".stripMargin))
    val settings = ConfigSource.fromConfig(config).at("waves.wallet").loadOrThrow[WalletSettings]

    settings.seed should be(Some(ByteStr.decodeBase58("BASE58SEED").get))
    settings.password should be(Some("some string as password"))
  }
}
