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
    // IPs (or hostnames) that are NEVER blacklisted even when enable-blacklisting=yes. Decoupled
    // from known-peers on purpose: the main node keeps known-peers=[] + peers-exchange=no to avoid a
    // handshake-collision loop, yet must still exempt the committee (e.g. the shared LKE egress IP)
    // from a transient-validation IP ban that would knock it below 2/3 finality. Default empty.
    blacklistExempt: Seq[String] = Nil,
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
    // Consecutive scheduleConnectTask ticks with zero connections AND no available candidate
    // before the in-process stall detector self-heals by clearing suspension (never blacklist).
    // Default 900: while the detector is actively counting (hasConnections == false, i.e. a real
    // stall), scheduleConnectTask's delay ALWAYS resolves to AverageHandshakePeriod (~1s +/- jitter,
    // see NetworkServer.scheduleConnectTask/AverageHandshakePeriod) -- the 5-second branch there only
    // applies when connections already exist, which can't be true while this counter is incrementing.
    // So the tick interval during any counted stall is ~1s, not a "1-5s" range, and 900 ticks is
    // ~15 minutes, matching the external peer-watchdog.yml debounce it replaces -- see
    // docs/superpowers/plans/2026-09-12-inprocess-peer-stall-detection.md. Deliberately at least
    // as conservative as what it replaces, not more aggressive. NOTE: this threshold's real-world
    // time window is coupled to AverageHandshakePeriod's value in NetworkServer.scala -- if that
    // constant ever changes, this threshold's effective window changes silently with it (no compile
    // error), so keep the two in sync when tuning either one.
    peerStallThreshold: Int = 900,
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
