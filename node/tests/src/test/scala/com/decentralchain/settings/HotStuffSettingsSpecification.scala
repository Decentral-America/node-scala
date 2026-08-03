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
                          |authoritative = false
      """.stripMargin)
    settings.enabled should be(true)
    settings.roundTimeout should be(1200.millis)
    settings.settledDepth should be(3)
    settings.authoritative should be(false)
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

  it should "default authoritative to false in the reference config (safety gate)" in {
    val settings = ConfigSource.fromConfig(ConfigFactory.load()).at("dcc.hotstuff").loadOrThrow[HotStuffSettings]
    settings.authoritative should be(false)
  }

  it should "reject authoritative=true when enabled=false" in {
    assertThrows[IllegalArgumentException] {
      HotStuffSettings(enabled = false, roundTimeout = 1200.millis, authoritative = true)
    }
  }

  it should "allow authoritative=true when enabled=true" in {
    val settings = HotStuffSettings(enabled = true, roundTimeout = 1200.millis, authoritative = true)
    settings.authoritative should be(true)
  }
}
