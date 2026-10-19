package com.decentralchain.events

import com.decentralchain.events.settings.BlockchainUpdatesSettings
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class BlockchainUpdatesSettingsSpec extends AnyFlatSpec with Matchers {
  private def load(extra: String) =
    ConfigSource
      .fromConfig(ConfigFactory.parseString(extra).withFallback(ConfigFactory.parseResources("application.conf")))
      .at("dcc.blockchain-updates")
      .loadOrThrow[BlockchainUpdatesSettings]

  "bind-address" should "default to all interfaces, so behaviour is unchanged" in {
    load("").bindAddress shouldBe "0.0.0.0"
  }

  it should "be configurable, e.g. loopback only" in {
    load("""dcc.blockchain-updates.bind-address = "127.0.0.1"""").bindAddress shouldBe "127.0.0.1"
  }
}
