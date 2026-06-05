package com.decentralchain.settings

import com.decentralchain.network.TrafficLogger
import com.decentralchain.utils.*
import java.io.File
import java.net.{InetSocketAddress, URI}
import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.FiniteDuration
import pureconfig.*

case class NetworkSettings(
    file: Option[File],
    bindAddress: Option[String],
    port: Option[Int],
    declaredAddress: Option[String],
    nodeName: Option[String],
    nonce: Option[Long],
    knownPeers: Seq[String],
    peersDataResidenceTime: FiniteDuration,
    blackListResidenceTime: FiniteDuration,
    breakIdleConnectionsTimeout: FiniteDuration,
    maxInboundConnections: Int,
    maxOutboundConnections: Int,
    maxSingleHostConnections: Int,
    minConnections: Option[Int],
    connectionTimeout: FiniteDuration,
    maxUnverifiedPeers: Int,
    enablePeersExchange: Boolean,
    enableBlacklisting: Boolean,
    peersBroadcastInterval: FiniteDuration,
    handshakeTimeout: FiniteDuration,
    suspensionResidenceTime: FiniteDuration,
    receivedTxsCacheTimeout: FiniteDuration,
    trafficLogger: TrafficLogger.Settings
) derives ConfigReader {

  val derivedDeclaredAddress: Option[InetSocketAddress] = declaredAddress.map { address =>
    val uri = new URI(s"my://$address")
    new InetSocketAddress(uri.getHost, uri.getPort)
  }

  val derivedNonce: Long = nonce.getOrElse(NetworkSettings.randomNonce)

  val derivedNodeName: String = nodeName.getOrElse(s"Node-$derivedNonce")
  require(
    derivedNodeName.utf8Bytes.length <= NetworkSettings.MaxNodeNameBytesLength,
    s"Node name should have length less than ${NetworkSettings.MaxNodeNameBytesLength} bytes"
  )

  val derivedBindAddress: Option[InetSocketAddress] = for {
    addr <- bindAddress
    p    <- port
  } yield new InetSocketAddress(addr, p)

  val maxConnectionsPerHost: Int = maxSingleHostConnections
}

object NetworkSettings {
  val MaxNodeNameBytesLength = 127

  def randomNonce: Long = {
    val base = 1000
    val rng  = ThreadLocalRandom.current()
    (rng.nextInt(base) + base) * rng.nextInt(base) + rng.nextInt(base)
  }
}
