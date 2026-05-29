package com.decentralchain.network

import com.google.common.base.Ticker
import com.typesafe.config.ConfigFactory
import com.decentralchain.settings.NetworkSettings
import com.decentralchain.test.FeatureSpec
import org.scalatest.GivenWhenThen
import pureconfig.ConfigSource

import java.net.{InetAddress, InetSocketAddress}

class BlacklistSpecification extends FeatureSpec with GivenWhenThen {
  private val config = ConfigFactory
    .parseString("""dcc.network {
                   |  known-peers = []
                   |  file = null
                   |  black-list-residence-time: 1s
                   |}""".stripMargin)
    .withFallback(ConfigFactory.load())
    .resolve()

  private val networkSettings = ConfigSource.fromConfig(config).at("dcc.network").loadOrThrow[NetworkSettings]
  private var timestamp       = 0L

  info("As a Peer")
  info("I want to blacklist other peers for certain time")
  info("So I can give them another chance after")

  Feature("Blacklist") {
    Scenario("Peer blacklist another peer") {
      Given("Peer database is empty")
      val peerDatabase = new PeerDatabaseImpl(
        networkSettings,
        new Ticker {
          override def read(): Long = timestamp
        }
      )

      def isBlacklisted(address: InetSocketAddress) = peerDatabase.isBlacklisted(address.getAddress)

      assert(peerDatabase.knownPeers.isEmpty)
      assert(peerDatabase.detailedBlacklist.isEmpty)

      When("Peer adds another peer to knownPeers")
      val address = new InetSocketAddress(InetAddress.getByName("localhost"), 1234)
      peerDatabase.touch(address)
      assert(peerDatabase.knownPeers.contains(address))
      assert(!isBlacklisted(address))

      And("Peer blacklists another peer")
      peerDatabase.blacklist(address.getAddress, "")
      assert(isBlacklisted(address))
      assert(!peerDatabase.knownPeers.contains(address))

      And("Peer waits for some time")
      timestamp += networkSettings.blackListResidenceTime.toNanos + 500

      Then("Another peer disappear from blacklist")
      assert(!isBlacklisted(address))

      And("Another peer became known")
      assert(peerDatabase.knownPeers.contains(address))
    }
  }
}
