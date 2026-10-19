package com.decentralchain.settings

import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.test.FlatSpec

import java.io.File
import scala.jdk.CollectionConverters.*

class NetworkDefaultsSpecification extends FlatSpec {
  private val wavesSeeds =
    Seq("159.69.126.149", "94.130.105.239", "159.69.126.153", "94.130.172.201", "168.119.116.189", "135.181.87.72", "35.158.218.156", "52.48.34.89")

  private def peersOf(t: String) =
    loadConfig(ConfigFactory.parseString(s"""dcc { directory = "/tmp/dcc-nd", blockchain.type = $t }"""))
      .getStringList("dcc.network.known-peers")
      .asScala
      .toSeq

  "testnet defaults" should "dial the DCC testnet seed by DNS, not Waves nodes" in {
    peersOf("TESTNET") shouldBe Seq("testnet-node.decentralchain.io:6868")
  }

  "mainnet defaults" should "contain no Waves nodes" in {
    peersOf("MAINNET") shouldBe empty
  }

  "network-defaults.conf" should "not mention any Waves seed IP" in {
    val text = scala.io.Source.fromResource("network-defaults.conf").mkString
    wavesSeeds.foreach(ip => text should not include ip)
  }

  "node/decentralchain-testnet.conf" should "describe the live DCC testnet chain" in {
    val f   = new File(sys.props.getOrElse("user.dir", ".")).toPath.resolveSibling("decentralchain-testnet.conf").toFile
    val alt = new File("node/decentralchain-testnet.conf")
    val src = if (f.exists()) f else alt
    val cfg = loadConfig(ConfigFactory.parseFile(src, ConfigParseOptions.defaults().setAllowMissing(false)))
    val bs  = BlockchainSettings.fromRootConfig(cfg)
    bs.genesisSettings.signature shouldBe Some(KnownChainParameters.DccTestnetRelaunchGenesis)
    bs.functionalitySettings.hotStuffEquivocationPenaltyHeight shouldBe 60000
    bs.functionalitySettings.finalityHardeningHeight shouldBe 90000
    cfg.getStringList("dcc.network.known-peers").asScala.toSeq shouldBe Seq("testnet-node.decentralchain.io:6868")
    cfg.getInt("dcc.network.port") shouldBe 6868
    UnknownConfigKeys.check(cfg) shouldBe Right(Seq.empty)
  }
}
