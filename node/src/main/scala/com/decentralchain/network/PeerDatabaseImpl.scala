package com.decentralchain.network

import com.google.common.base.Ticker
import com.google.common.cache.CacheBuilder
import com.google.common.collect.EvictingQueue
import com.decentralchain.settings.NetworkSettings
import com.decentralchain.utils.{JsonFileStorage, ScorexLogging}
import io.netty.channel.Channel
import io.netty.channel.socket.nio.NioSocketChannel

import java.net.{InetAddress, InetSocketAddress, URI}
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import scala.annotation.tailrec
import scala.collection.*
import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

class PeerDatabaseImpl(settings: NetworkSettings, ticker: Ticker = Ticker.systemTicker()) extends PeerDatabase with AutoCloseable with ScorexLogging {
  private def cache[T <: AnyRef](timeout: FiniteDuration) =
    CacheBuilder
      .newBuilder()
      .ticker(ticker)
      .expireAfterWrite(timeout.toMillis, TimeUnit.MILLISECONDS)
      .build[T, java.lang.Long]()

  private type PeersPersistenceType = Set[String]
  private val peersPersistence = cache[InetSocketAddress](settings.peersDataResidenceTime)
  private val blacklist        = cache[InetAddress](settings.blackListResidenceTime)
  private val suspension       = cache[InetAddress](settings.suspensionResidenceTime)
  private val reasons          = mutable.Map.empty[InetAddress, String]
  private val unverifiedPeers  = EvictingQueue.create[InetSocketAddress](settings.maxUnverifiedPeers)

  private val IPAndPort = """(\d+)\.(\d+)\.(\d+)\.(\d+):(\d+)""".r

  for (f <- settings.file if f.exists && f.isFile && f.length > 0) try {
    JsonFileStorage.load[PeersPersistenceType](f.getCanonicalPath).map {
      case IPAndPort(a, b, c, d, port) =>
        addCandidate(new InetSocketAddress(InetAddress.getByAddress(Array(a, b, c, d).map(_.toInt.toByte)), port.toInt))
      case _ =>
    }

    log.info(s"Loaded ${unverifiedPeers.size} known peer(s) from ${f.getName}")
  } catch {
    case NonFatal(e) => log.info("Legacy or corrupted peers.dat, ignoring, starting all over from known-peers...", e)
  }

  override def addCandidate(socketAddress: InetSocketAddress): Boolean = unverifiedPeers.synchronized {
    val r = !socketAddress.getAddress.isAnyLocalAddress &&
      !(socketAddress.getAddress.isLoopbackAddress && settings.derivedBindAddress.exists(_.getPort == socketAddress.getPort)) &&
      Option(peersPersistence.getIfPresent(socketAddress)).isEmpty &&
      !unverifiedPeers.contains(socketAddress)
    if (r) unverifiedPeers.add(socketAddress)
    r
  }

  private def doTouch(socketAddress: InetSocketAddress, timestamp: Long): Unit = unverifiedPeers.synchronized {
    unverifiedPeers.removeIf(_ == socketAddress)
    peersPersistence.put(socketAddress, Option(peersPersistence.getIfPresent(socketAddress)).fold(timestamp)(_.toLong.max(timestamp)))
  }

  override def touch(socketAddress: InetSocketAddress): Unit = doTouch(socketAddress, System.currentTimeMillis())

  // Trusted addresses (committee generators / sentries / seeds) that are NEVER blacklisted, from the
  // dedicated `blacklist-exempt` config — decoupled from `known-peers` so a node can exempt a peer it
  // does NOT dial (the main node keeps known-peers=[] + peers-exchange=no to avoid a handshake-collision
  // loop, yet must still exempt the committee). Rationale: `blacklistAndClose` fires on transient/honest
  // conditions too (a peer briefly on a different fork tip, a validation failure during a normal reorg),
  // and a black-list-residence-time IP ban of an honest generator can drop a small finality committee
  // below its 2/3 threshold (the RC#2 loop) — worse still, LKE generators share one egress IP, so one
  // ban takes out the whole cluster. Resolved once at startup (never DNS on a netty handler thread);
  // entries may be "ip", "host", or "host:port" (only the address matters). See infra/MAINNET-LAUNCH.md §Phase 4.
  private lazy val blacklistExemptAddresses: Set[InetAddress] =
    settings.blacklistExempt.flatMap { entry =>
      val host = if (entry.contains(":")) entry.substring(0, entry.lastIndexOf(':')) else entry
      try InetAddress.getAllByName(host).toSeq
      catch { case NonFatal(e) => log.warn(s"Could not resolve blacklist-exempt entry '$entry': $e"); Nil }
    }.toSet

  def isBlacklistExempt(inetAddress: InetAddress): Boolean = blacklistExemptAddresses.contains(inetAddress)

  override def blacklist(inetAddress: InetAddress, reason: String): Unit =
    if (settings.enableBlacklisting) {
      if (isBlacklistExempt(inetAddress)) {
        // Exempt: drop from unverified + the channel still closes at the call site, but do NOT ban the
        // trusted peer. It may reconnect immediately (the brief suspend-on-close still applies).
        unverifiedPeers.synchronized(unverifiedPeers.removeIf(_.getAddress == inetAddress))
        log.debug(s"Not blacklisting $inetAddress ($reason) — in blacklist-exempt, trusted")
      } else {
        unverifiedPeers.synchronized {
          unverifiedPeers.removeIf(_.getAddress == inetAddress)
          blacklist.put(inetAddress, ticker.read())
          reasons.put(inetAddress, reason)
        }
      }
    }

  override def suspend(socketAddress: InetSocketAddress): Unit = getAddress(socketAddress).foreach { address =>
    unverifiedPeers.synchronized {
      // Bumped from trace to info (INCIDENT-GEN0-PEERS.md #11, 2026-07-07) --
      // this fires on every connection close for any reason and was
      // invisible at every log level actually tested.
      log.info(s"Suspending $socketAddress for ${settings.suspensionResidenceTime}")
      unverifiedPeers.removeIf(_ == socketAddress)
      suspension.put(address, System.currentTimeMillis())
    }
  }

  override def knownPeers: immutable.Map[InetSocketAddress, Long] = {
    peersPersistence.cleanUp() // run all deferred actions (expiration/listeners/etc)
    peersPersistence
      .asMap()
      .asScala
      .collect {
        case (addr, ts) if !(settings.enableBlacklisting && isBlacklisted(addr.getAddress)) => addr -> ts.toLong
      }
      .toMap
  }

  def isBlacklisted(address: InetAddress): Boolean = blacklist.asMap().containsKey(address)
  def isSuspended(address: InetAddress): Boolean   = suspension.asMap().containsKey(address)

  override def detailedBlacklist: immutable.Map[InetAddress, (Long, String)] =
    blacklist.asMap().asScala.view.mapValues(_.toLong).map { case (h, t) => h -> ((t, Option(reasons(h)).getOrElse(""))) }.toMap

  override def detailedSuspended: immutable.Map[InetAddress, Long] = suspension.asMap().asScala.view.mapValues(_.toLong).toMap

  private def resolvePeerAddress(addr: String): Seq[InetSocketAddress] = {
    val uri = new URI(s"node://$addr")
    require(uri.getPort > 0, s"invalid port ${uri.getPort}")
    InetAddress
      .getAllByName(uri.getHost)
      .view
      .map { ia =>
        new InetSocketAddress(ia, uri.getPort)
      }
      .toSeq
  }

  override def nextCandidate(excluded: immutable.Set[InetSocketAddress]): Option[InetSocketAddress] = unverifiedPeers.synchronized {
    def excludeAddress(isa: InetSocketAddress): Boolean =
      excluded(isa) || isBlacklisted(isa.getAddress) || isSuspended(isa.getAddress)

    @tailrec
    def nextUnverified(): Option[InetSocketAddress] =
      unverifiedPeers.poll() match {
        case null    => None
        case nonNull =>
          if (!excludeAddress(nonNull)) Some(nonNull) else nextUnverified()
      }

    val resolvedPeersFromConfig = settings.knownPeers
      .flatMap(p => resolvePeerAddress(p))

    val selectedNextUnverified = nextUnverified()

    val filteredKnownPeers = knownPeers.keySet.filterNot(excludeAddress)
    val randomKnownPeer    =
      (if (filteredKnownPeers.size > 1) filteredKnownPeers.view.drop(ThreadLocalRandom.current().nextInt(filteredKnownPeers.size))
       else filteredKnownPeers).headOption

    val selectedCandidate = resolvedPeersFromConfig
      .filterNot(excludeAddress)
      .headOption
      .orElse(selectedNextUnverified)
      .orElse(randomKnownPeer)

    if (selectedCandidate.isEmpty)
      log.trace(
        s"No candidate, excluded: [${excluded.mkString(",")}], known-peers = [${resolvedPeersFromConfig.mkString(",")}], " +
          s"unverified size: ${unverifiedPeers.size()}, peer cache size: ${peersPersistence.size()}, blacklist size: ${blacklist.size()}, suspension size: ${suspension.size()}"
      )

    selectedCandidate
  }

  def clearBlacklist(): Unit = {
    blacklist.invalidateAll()
    reasons.clear()
  }

  override def close(): Unit = settings.file.foreach { f =>
    val rawPeers = knownPeers.keySet.map(address => s"${address.getAddress.getHostAddress}:${address.getPort}")

    log.info(s"Saving ${rawPeers.size} known peer(s) to ${f.getName}")

    JsonFileStorage.save[PeersPersistenceType](rawPeers, f.getCanonicalPath)
  }

  override def blacklistAndClose(channel: Channel, reason: String): Unit = getRemoteAddress(channel).foreach { x =>
    log.debug(s"Blacklisting ${id(channel)}: $reason")
    blacklist(x.getAddress, reason)
    channel.close()
  }

  private def getAddress(socketAddress: InetSocketAddress): Option[InetAddress] = {
    val r = Option(socketAddress.getAddress)
    if (r.isEmpty) log.debug(s"Can't obtain an address from $socketAddress")
    r
  }

  private def getRemoteAddress(channel: Channel): Option[InetSocketAddress] = channel match {
    case x: NioSocketChannel => Option(x.remoteAddress())
    case x                   =>
      log.debug(s"Doesn't know how to get a remoteAddress from $x")
      None
  }
}
