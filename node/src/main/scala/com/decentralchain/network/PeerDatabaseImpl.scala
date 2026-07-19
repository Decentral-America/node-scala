package com.decentralchain.network

import com.google.common.base.Ticker
import com.google.common.cache.CacheBuilder
import com.google.common.collect.EvictingQueue
import com.decentralchain.settings.NetworkSettings
import com.decentralchain.utils.{JsonFileStorage, ScorexLogging}
import io.netty.channel.Channel
import io.netty.channel.socket.nio.NioSocketChannel

import java.net.{InetAddress, InetSocketAddress, URI}
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.*
import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*
import scala.util.control.NonFatal

class PeerDatabaseImpl(settings: NetworkSettings, ticker: Ticker = Ticker.systemTicker()) extends PeerDatabase with AutoCloseable with ScorexLogging {
  private def cache[T <: AnyRef](timeout: FiniteDuration, maxSize: Option[Long] = None) = {
    val builder = CacheBuilder
      .newBuilder()
      .ticker(ticker)
      .expireAfterWrite(timeout.toJava)
    maxSize.foreach(s => builder.maximumSize(s))
    builder.build[T, java.lang.Long]()
  }

  // Bound the verified-peer pool so an attacker who completes handshakes from many sybil IPs cannot grow it
  // without limit (eclipse / memory pressure). Sized generously relative to our connection budget.
  private val MaxVerifiedPeers: Long = math.max(1000L, (settings.maxInboundConnections + settings.maxOutboundConnections) * 4L)

  private type PeersPersistenceType = Set[String]
  private val peersPersistence = cache[InetSocketAddress](settings.peersDataResidenceTime, Some(MaxVerifiedPeers))
  private val blacklist        = cache[InetAddress](settings.blackListResidenceTime)
  private val suspension       = cache[InetAddress](settings.suspensionResidenceTime)
  private val reasons          = mutable.Map.empty[InetAddress, String]
  private val unverifiedPeers  = EvictingQueue.create[InetSocketAddress](settings.maxUnverifiedPeers)

  private val IPAndPort = """(\d+)\.(\d+)\.(\d+)\.(\d+):(\d+)""".r

  // Configured known-peers (seed nodes + our own validators/generators) are trusted infrastructure and must
  // NEVER be blacklisted or suspended: transient churn between our own forging nodes used to mutually
  // blacklist them and stall finality (the RC#2 peer-cycling incident), which forced the unsafe global
  // `enable-blacklisting = no` workaround. Exempting known-peers lets mainnet run with blacklisting ON (the
  // safe default that penalizes genuinely hostile peers) without ever penalizing our own mesh. Resolution is
  // cached and refreshed periodically so DNS is not touched on the hot path.
  private val KnownPeerRefreshMillis                             = 5 * 60 * 1000L
  private val knownPeerAddresses: AtomicReference[(Long, Set[InetAddress])] = new AtomicReference((0L, Set.empty))

  private def isKnownPeerAddress(address: InetAddress): Boolean = {
    val now          = System.currentTimeMillis()
    val (ts, cached) = knownPeerAddresses.get()
    val set =
      if (now - ts > KnownPeerRefreshMillis) {
        val resolved = settings.knownPeers.flatMap { p =>
          try resolvePeerAddress(p).map(_.getAddress)
          catch { case NonFatal(_) => Nil }
        }.toSet
        knownPeerAddresses.set((now, resolved))
        resolved
      } else cached
    set.contains(address)
  }

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

  // Low-level, unconditional blacklist (respects only the global enable-blacklisting switch). The known-peer
  // leniency lives in blacklistAndClose (the automatic protocol-trigger path), not here — so the operator
  // Debug API and any deliberate call blacklist exactly what they ask for.
  override def blacklist(inetAddress: InetAddress, reason: String): Unit =
    if (settings.enableBlacklisting) {
      unverifiedPeers.synchronized {
        unverifiedPeers.removeIf(_.getAddress == inetAddress)
        blacklist.put(inetAddress, ticker.read())
        reasons.put(inetAddress, reason)
      }
    }

  override def suspend(socketAddress: InetSocketAddress): Unit = getAddress(socketAddress).foreach { address =>
    unverifiedPeers.synchronized {
      log.trace(s"Suspending $socketAddress")
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
        case null => None
        case nonNull =>
          if (!excludeAddress(nonNull)) Some(nonNull) else nextUnverified()
      }

    // Anti-eclipse: prefer candidates in a /16 we are not already connected to, so a sybil cluster in a single
    // subnet cannot monopolize our outbound slots. Falls back to the full pool if no diverse peer is available.
    val excludedSubnets: Set[Int] = excluded.flatMap(isa => subnetKey(isa.getAddress))
    def isDiverse(isa: InetSocketAddress): Boolean = !subnetKey(isa.getAddress).exists(excludedSubnets.contains)

    val resolvedPeersFromConfig = settings.knownPeers
      .flatMap(p => resolvePeerAddress(p))

    val selectedNextUnverified = nextUnverified()

    val filteredKnownPeers = knownPeers.keySet.filterNot(excludeAddress)
    // Prefer verified peers in an unrepresented subnet; only fall back to same-subnet peers if none are diverse.
    val knownPeerPool      = filteredKnownPeers.filter(isDiverse) match {
      case diverse if diverse.nonEmpty => diverse
      case _                           => filteredKnownPeers
    }
    val randomKnownPeer =
      (if (knownPeerPool.size > 1) knownPeerPool.view.drop(ThreadLocalRandom.current().nextInt(knownPeerPool.size)) else knownPeerPool).headOption

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

  // Policy for the automatic protocol-trigger path (malformed/invalid messages, handshake failures, fork
  // suspicion, ...). A trusted known-peer/validator trips these on transient, honest conditions too (reorg
  // competition), and a full blacklist (15m IP ban) can knock it out of the finality committee — the RC#2
  // stall. So suspend a known-peer briefly instead of banning it; only untrusted peers get the full blacklist.
  // package-private so it is unit-testable without a live Netty channel.
  private[network] def blacklistOrSuspend(socketAddress: InetSocketAddress, reason: String): Unit =
    if (isKnownPeerAddress(socketAddress.getAddress)) {
      log.debug(s"Suspending (not blacklisting) known-peer $socketAddress: $reason")
      suspend(socketAddress)
    } else {
      log.debug(s"Blacklisting $socketAddress: $reason")
      blacklist(socketAddress.getAddress, reason)
    }

  override def blacklistAndClose(channel: Channel, reason: String): Unit = getRemoteAddress(channel).foreach { x =>
    blacklistOrSuspend(x, reason)
    channel.close()
  }

  // /16 subnet key for IPv4 (top two octets); None for IPv6/unknown (treated as always-diverse — IPv6 space
  // is too large to bucket meaningfully here). Used to spread outbound connections across subnets.
  private def subnetKey(address: InetAddress): Option[Int] = {
    val b = address.getAddress
    if (b != null && b.length == 4) Some(((b(0) & 0xff) << 8) | (b(1) & 0xff)) else None
  }

  private def getAddress(socketAddress: InetSocketAddress): Option[InetAddress] = {
    val r = Option(socketAddress.getAddress)
    if (r.isEmpty) log.debug(s"Can't obtain an address from $socketAddress")
    r
  }

  private def getRemoteAddress(channel: Channel): Option[InetSocketAddress] = channel match {
    case x: NioSocketChannel => Option(x.remoteAddress())
    case x =>
      log.debug(s"Doesn't know how to get a remoteAddress from $x")
      None
  }
}
