package com.decentralchain.network

import com.decentralchain.Version
import com.decentralchain.metrics.Metrics
import com.decentralchain.settings.*
import com.decentralchain.state.Cast
import com.decentralchain.utils.ScorexLogging
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.channel.*
import io.netty.channel.group.ChannelGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import io.netty.util.concurrent.{DefaultThreadFactory, GenericFutureListener}
import monix.reactive.Observable
import org.influxdb.dto.Point

import java.net.{InetSocketAddress, NetworkInterface, SocketAddress}
import java.nio.channels.ClosedChannelException
import java.util.concurrent.{ConcurrentHashMap, ThreadLocalRandom}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

trait NetworkServer {
  def connect(remoteAddress: InetSocketAddress): Unit
  def shutdown(): Unit
  def closedChannels: Observable[Channel]
}

object NetworkServer extends ScorexLogging {
  val MaxFrameLength: Int            = 100 * 1024 * 1024
  private val AverageHandshakePeriod = 1.second
  private val LengthFieldSize        = 4

  def apply(
      applicationName: String,
      networkSettings: NetworkSettings,
      peerDatabase: PeerDatabase,
      allChannels: ChannelGroup,
      peerInfo: ConcurrentHashMap[Channel, PeerInfo],
      protocolSpecificPipeline: => Seq[ChannelHandlerAdapter]
  ): NetworkServer = {
    @volatile var shutdownInitiated = false

    val bossGroup   = new MultiThreadIoEventLoopGroup(0, new DefaultThreadFactory("nio-boss-group", true), NioIoHandler.newFactory());
    val workerGroup = new MultiThreadIoEventLoopGroup(0, new DefaultThreadFactory("nio-worker-group", true), NioIoHandler.newFactory());
    val handshake   = Handshake(
      applicationName,
      Version.VersionTuple,
      networkSettings.derivedNodeName,
      networkSettings.derivedNonce,
      networkSettings.derivedDeclaredAddress
    )

    val excludedAddresses: Set[InetSocketAddress] =
      networkSettings.derivedBindAddress.fold(Set.empty[InetSocketAddress]) { bindAddress =>
        val isLocal        = Option(bindAddress.getAddress).exists(_.isAnyLocalAddress)
        val localAddresses = if (isLocal) {
          NetworkInterface.getNetworkInterfaces.asScala
            .flatMap(_.getInetAddresses.asScala.map(a => new InetSocketAddress(a, bindAddress.getPort)))
            .toSet
        } else Set(bindAddress)

        localAddresses ++ networkSettings.derivedDeclaredAddress.toSet
      }

    val lengthFieldPrepender = new LengthFieldPrepender(4)

    // There are two error handlers by design. WriteErrorHandler adds a future listener to make sure writes to network
    // succeed. It is added to the head of pipeline (it's the closest of the two to actual network), because some writes
    // are initiated from the middle of the pipeline (e.g. extension requests). FatalErrorHandler, on the other hand,
    // reacts to inbound exceptions (the ones thrown during channelRead). It is added to the tail of pipeline to handle
    // exceptions bubbling up from all the handlers below. When a fatal exception is caught (like OutOfMemory), the
    // application is terminated.
    val writeErrorHandler = new WriteErrorHandler
    val fatalErrorHandler = new FatalErrorHandler

    val inboundConnectionFilter =
      new InboundConnectionFilter(peerDatabase, networkSettings.maxInboundConnections, networkSettings.maxConnectionsPerHost)

    val (channelClosedHandler, closedChannelsSubject) = ChannelClosedHandler()
    val peerConnectionsMap                            = new ConcurrentHashMap[PeerKey, Channel](10, 0.9f, 10)
    val serverHandshakeHandler = new HandshakeHandler.Server(handshake, peerInfo, peerConnectionsMap, peerDatabase, allChannels)

    def pipelineTail: Seq[ChannelHandlerAdapter] =
      Seq(
        lengthFieldPrepender,
        new LengthFieldBasedFrameDecoder(MaxFrameLength, 0, LengthFieldSize, 0, LengthFieldSize)
      ) ++ protocolSpecificPipeline ++
        Seq(writeErrorHandler, channelClosedHandler, fatalErrorHandler)

    val serverChannel = networkSettings.derivedBindAddress.map { bindAddress =>
      new ServerBootstrap()
        .group(bossGroup, workerGroup)
        .channel(classOf[NioServerSocketChannel])
        .childHandler(
          new PipelineInitializer(
            Seq(
              inboundConnectionFilter,
              new BrokenConnectionDetector(networkSettings.breakIdleConnectionsTimeout),
              new HandshakeDecoder(peerDatabase),
              new HandshakeTimeoutHandler(networkSettings.handshakeTimeout),
              serverHandshakeHandler
            ) ++ pipelineTail
          )
        )
        .bind(bindAddress)
        .channel()
    }

    val outgoingChannels = new ConcurrentHashMap[InetSocketAddress, Channel]

    val clientHandshakeHandler = new HandshakeHandler.Client(handshake, peerInfo, peerConnectionsMap, peerDatabase, allChannels)

    val bootstrap = new Bootstrap()
      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, networkSettings.connectionTimeout.toMillis.toInt: Integer)
      .group(workerGroup)
      .channel(classOf[NioSocketChannel])
      .handler(
        new PipelineInitializer(
          Seq(
            new BrokenConnectionDetector(networkSettings.breakIdleConnectionsTimeout),
            new HandshakeDecoder(peerDatabase),
            // Was `if (peerConnectionsMap.isEmpty) AverageHandshakePeriod else
            // networkSettings.handshakeTimeout` -- AverageHandshakePeriod is
            // 1 second, meant for how *often* to retry connecting while
            // disconnected (see scheduleConnectTask below), not for how long
            // an in-flight handshake gets before being killed. Reusing it
            // here gave every reconnect attempt only 1s to complete a full
            // TCP + DCC handshake round trip -- exactly when reconnecting
            // after losing all peers, the worst possible moment to make the
            // timeout 30x tighter than normal. Any attempt slower than 1s
            // (routine for a cross-region path, e.g. LKE Frankfurt <-> a
            // Newark VPS) got suspended for 300s and retried into the same
            // 1s ceiling again -- a self-reinforcing connect/suspend loop.
            // Confirmed live via INCIDENT-GEN0-PEERS.md #11: newly-added
            // info-level logging caught a connection established then
            // suspended+closed 1.5s later with no successful handshake ever
            // logged in between. Always use the real handshake-timeout.
            new HandshakeTimeoutHandler(networkSettings.handshakeTimeout),
            clientHandshakeHandler
          ) ++ pipelineTail
        )
      )

    def formatOutgoingChannelEvent(channel: Channel, event: String) = s"${id(channel)} $event, outgoing channel count: ${outgoingChannels.size()}"

    def handleOutgoingChannelClosed(remoteAddress: InetSocketAddress)(closeFuture: ChannelFuture): Unit = {
      outgoingChannels.remove(remoteAddress, closeFuture.channel())
      if (!shutdownInitiated) peerDatabase.suspend(remoteAddress)

      // Bumped from trace/debug to info (INCIDENT-GEN0-PEERS.md #11, 2026-07-07):
      // this is the only place that logs why an outbound peer connection
      // actually closed, and it was invisible at every log level tested,
      // including targeted TRACE overrides -- made diagnosing repeated
      // gen-0/gen-1/val-0 disconnects from main impossible without a rebuild.
      if (closeFuture.isSuccess)
        log.info(formatOutgoingChannelEvent(closeFuture.channel(), "Channel closed (expected)"))
      else
        log.info(
          formatOutgoingChannelEvent(
            closeFuture.channel(),
            s"Channel closed: ${Option(closeFuture.cause()).map(_.getMessage).getOrElse("no message")}"
          )
        )

      logConnections()
    }

    def handleConnectionAttempt(remoteAddress: InetSocketAddress)(thisConnFuture: ChannelFuture): Unit = {
      if (thisConnFuture.isSuccess) {
        log.info(formatOutgoingChannelEvent(thisConnFuture.channel(), "Connection established"))
        thisConnFuture.channel().closeFuture().addListener((f: ChannelFuture) => handleOutgoingChannelClosed(remoteAddress)(f))
      } else if (thisConnFuture.cause() != null) {
        peerDatabase.suspend(remoteAddress)
        outgoingChannels.remove(remoteAddress, thisConnFuture.channel())
        thisConnFuture.cause() match {
          case e: ClosedChannelException =>
            // this can happen when the node is shut down before connection attempt succeeds
            log.info(
              formatOutgoingChannelEvent(
                thisConnFuture.channel(),
                s"Channel closed by connection issue: ${Option(e.getMessage).getOrElse("no message")}"
              )
            )
          case other => log.info(formatOutgoingChannelEvent(thisConnFuture.channel(), other.getMessage))
        }
      }
      logConnections()
    }

    def doConnect(remoteAddress: InetSocketAddress): Unit =
      outgoingChannels.computeIfAbsent(
        remoteAddress,
        _ => {
          val newConnFuture = bootstrap.connect(remoteAddress)

          log.trace(s"${id(newConnFuture.channel())} Connecting to $remoteAddress")
          newConnFuture.addListener((f: ChannelFuture) => handleConnectionAttempt(remoteAddress)(f)).channel()
        }
      )

    def logConnections(): Unit = {
      def mkAddressString(addresses: IterableOnce[SocketAddress]) =
        addresses.iterator.map(_.toString).toVector.sorted.mkString("[", ",", "]")

      val incoming = peerInfo.values().asScala.view.map(_.remoteAddress).filterNot(outgoingChannels.containsKey)

      lazy val incomingStr = mkAddressString(incoming)
      lazy val outgoingStr = mkAddressString(outgoingChannels.keySet.iterator().asScala)

      val all = peerInfo.values().iterator().asScala.flatMap(_.remoteAddress.cast[InetSocketAddress])

      log.trace(s"Outgoing: $outgoingStr ++ incoming: $incomingStr")

      Metrics.write(
        Point
          .measurement("connections")
          .addField("outgoing", outgoingStr)
          .addField("incoming", incomingStr)
          .addField("n", all.size)
      )
    }

    def scheduleConnectTask(): Unit = if (!shutdownInitiated) {
      val delay = (if (peerConnectionsMap.isEmpty || networkSettings.minConnections.exists(_ > peerConnectionsMap.size())) AverageHandshakePeriod
                   else 5.seconds) +
        (ThreadLocalRandom.current().nextInt(1000) - 500).millis // add some noise so that nodes don't attempt to connect to each other simultaneously

      workerGroup.schedule(delay) {
        if (outgoingChannels.size() < networkSettings.maxOutboundConnections) {
          val all = peerInfo.values().iterator().asScala.flatMap(_.remoteAddress.cast[InetSocketAddress])
          peerDatabase
            .nextCandidate(excluded = excludedAddresses ++ all)
            .foreach(doConnect)
        }

        scheduleConnectTask()
      }
    }

    scheduleConnectTask()

    new NetworkServer {
      override def connect(remoteAddress: InetSocketAddress): Unit = doConnect(remoteAddress)

      override def shutdown(): Unit =
        try {
          shutdownInitiated = true
          serverChannel.foreach(_.close().await())
          log.debug("Unbound server")
          allChannels.close().await()
          log.debug("Closed all channels")
        } finally {
          workerGroup.shutdownGracefully().await()
          bossGroup.shutdownGracefully().await()
          channelClosedHandler.shutdown()
        }

      override val closedChannels: Observable[Channel] = closedChannelsSubject
    }
  }
}
