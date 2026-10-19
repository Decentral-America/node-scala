package com.decentralchain.settings

import com.typesafe.config.ConfigFactory
import com.decentralchain.test.FlatSpec

class UnknownConfigKeysSpecification extends FlatSpec {
  private def cfg(s: String) = loadConfig(ConfigFactory.parseString(s"""dcc { directory = "/tmp/dcc-ukc"\n $s }"""))

  "a misspelt consensus key" should "be fatal" in {
    val r = UnknownConfigKeys.check(
      cfg("""blockchain { type = CUSTOM, custom { functionality { hot-stuff-equivocation-penalty-heigth = 60000 } } }""")
    )
    r.isLeft shouldBe true
    r.left.toOption.get should include("hot-stuff-equivocation-penalty-heigth")
  }

  "a known consensus key" should "pass" in {
    UnknownConfigKeys.check(cfg("""blockchain { type = CUSTOM, custom { functionality { finality-hardening-height = 90000 } } }""")) shouldBe Right(
      Seq.empty
    )
  }

  "rest-api.cors" should "only warn" in {
    UnknownConfigKeys.check(cfg("""rest-api { cors = yes }""")) shouldBe Right(Seq("Unknown config key dcc.rest-api.cors is ignored"))
  }

  "blockchain-updates.bind-address" should "be a known key, and a misspelt one only warns" in {
    UnknownConfigKeys.check(cfg("""blockchain-updates { bind-address = "127.0.0.1" }""")) shouldBe Right(Seq.empty)
    UnknownConfigKeys.check(cfg("""blockchain-updates { bind-adress = "127.0.0.1" }""")) shouldBe Right(
      Seq("Unknown config key dcc.blockchain-updates.bind-adress is ignored")
    )
  }

  "the shipped defaults" should "produce no warnings at all" in {
    UnknownConfigKeys.check(cfg("")) shouldBe Right(Seq.empty)
  }
}
