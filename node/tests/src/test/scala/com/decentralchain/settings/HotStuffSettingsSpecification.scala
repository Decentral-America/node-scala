package com.decentralchain.settings

import com.typesafe.config.ConfigFactory
import com.decentralchain.test.FlatSpec
import pureconfig.ConfigSource

import scala.concurrent.duration.*

class HotStuffSettingsSpecification extends FlatSpec {
  private def load(hotstuff: String): HotStuffSettings =
    ConfigSource
      .fromConfig(ConfigFactory.parseString(s"dcc { hotstuff { $hotstuff } }").resolve())
      .at("dcc.hotstuff")
      .loadOrThrow[HotStuffSettings]

  "HotStuffSettings" should "read explicit values" in {
    val settings = load("""
                          |enabled = true
                          |round-timeout = 1200ms
                          |settled-depth = 3
      """.stripMargin)
    settings.enabled should be(true)
    settings.roundTimeout should be(1200.millis)
    settings.settledDepth should be(3)
  }

  it should "reject a settled-depth below 1 when enabled" in {
    assertThrows[IllegalArgumentException] {
      HotStuffSettings(enabled = true, roundTimeout = 1200.millis, settledDepth = 0)
    }
  }

  it should "default to disabled in the reference config (safety gate)" in {
    val settings = ConfigSource.fromConfig(ConfigFactory.load()).at("dcc.hotstuff").loadOrThrow[HotStuffSettings]
    settings.enabled should be(false)
  }

  it should "reject a non-positive round timeout when enabled" in {
    assertThrows[IllegalArgumentException] {
      HotStuffSettings(enabled = true, roundTimeout = Duration.Zero)
    }
  }

  it should "allow a non-positive round timeout when disabled (flag off = inert)" in {
    HotStuffSettings(enabled = false, roundTimeout = Duration.Zero).enabled should be(false)
  }
}
