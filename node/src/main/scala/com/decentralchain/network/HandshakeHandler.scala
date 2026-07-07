package com.decentralchain.network

import com.decentralchain.network.Handshake.InvalidHandshakeException
import com.decentralchain.utils.ScorexLogging
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.group.ChannelGroup
import io.netty.handler.codec.ReplayingDecoder
import io.netty.util.AttributeKey
import io.netty.util.concurrent.ScheduledFuture

import java.net.InetSocketAddress
import java.util
import java.util.concurrent.{ConcurrentMap, TimeUnit}
import scala.concurrent.duration.FiniteDuration

class HandshakeDecoder(peerDatabase: PeerDatabase) extends ReplayingDecoder[Void] with ScorexLogging {
  override def decode(ctx: ChannelHandlerContext, in: ByteBuf, out: util.List[AnyRef]): Unit =
    try {
      out.add(Handshake.decode(in))
      ctx.pipeline().remove(this)
    } catch {
      case e: InvalidHandshakeException => block(ctx, e)
    }

  protected def block(ctx: ChannelHandlerContext, e: Throwable): Unit = {
    peerDatabase.blacklistAndClose(ctx.channel(), e.getMessage)
  }
}

case object HandshakeTimeoutExpired

class HandshakeTimeoutHandler(handshakeTimeout: FiniteDuration) extends ChannelInboundHandlerAdapter with ScorexLogging {
  private var timeout: Option[ScheduledFuture[?]] = None

  private def cancelTimeout(): Unit = timeout.foreach(_.cancel(true))

  override def channelRegistered(ctx: ChannelHandlerContext): Unit = {
    log.trace(s"${id(ctx)} Scheduling handshake timeout in $handshakeTimeout")
    timeout = Some(
      ctx
        .channel()
        .eventLoop()
        .schedule(
          { () =>
            log.trace(s"${id(ctx)} Firing handshake timeout expired")
            ctx.fireChannelRead(HandshakeTimeoutExpired)
          },
          handshakeTimeout.toMillis,
          TimeUnit.MILLISECONDS
        )
    )

    super.channelRegistered(ctx)
  }

  override def channelUnregistered(ctx: ChannelHandlerContext): Unit = {
    cancelTimeout()
    super.channelUnregistered(ctx)
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case hs: Handshake =>
      cancelTimeout()
      super.channelRead(ctx, hs)
    case other =>
      super.channelRead(ctx, other)
  }
}

abstract class HandshakeHandler(
    localHandshake: Handshake,
    establishedConnections: ConcurrentMap[Channel, PeerInfo],
    peerConnections: ConcurrentMap[PeerKey, Channel],
    peerDatabase: PeerDatabase,
    allChannels: ChannelGroup
) extends ChannelInboundHandlerAdapter
    with ScorexLogging {

  import HandshakeHandler.*

  protected def suspendAndClose(msg: => String, verifiedRemoteAddress: Option[InetSocketAddress], ctx: ChannelHandlerContext): Unit = {
    log.debug(s"${id(ctx)} $msg")
    verifiedRemoteAddress.foreach(peerDatabase.suspend)
    ctx.close()
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case HandshakeTimeoutExpired =>
      log.trace(s"Timeout expired while waiting for handshake: ${id(ctx.channel())}")
      ctx.channel().remoteAddress() match {
        case isa: InetSocketAddress => peerDatabase.suspend(isa)
        case _                      =>
      }
      ctx.close()
    case remoteHandshake: Handshake =>
      val verifiedDeclaredAddress = remoteHandshake.declaredAddress.filter(_ == ctx.channel().remoteAddress())

      if (localHandshake.applicationName != remoteHandshake.applicationName)
        suspendAndClose(
          s"Remote application name ${remoteHandshake.applicationName} does not match local ${localHandshake.applicationName}",
          verifiedDeclaredAddress,
          ctx
        )
      else if (!versionIsSupported(remoteHandshake.applicationVersion))
        suspendAndClose(s"Remote application version ${remoteHandshake.applicationVersion} is not supported", verifiedDeclaredAddress, ctx)
      else {
        verifiedDeclaredAddress.foreach { vda =>
          ctx.channel().attr(NodeDeclaredAddressAttributeKey).set(vda)
          peerDatabase.touch(vda)
        }

        PeerKey(ctx, remoteHandshake.nodeNonce) match {
          case None =>
            log.warn(s"Can't get PeerKey from ${id(ctx)}")
            ctx.close()

          case Some(key) =>
            val previousPeer = peerConnections.putIfAbsent(key, ctx.channel())
            // A previous entry can be a channel that has already died (TCP
            // drop, remote reset) but whose closeFuture listener has not
            // run yet -- that removal and this handshake are racing on
            // separate event loops. Treating a dead previousPeer as a live
            // duplicate closed the new, working channel and kept the dead
            // one registered, so the connection never became usable and
            // the only trace was a debug-level log line. Evict a dead
            // previousPeer and let the new channel take its place instead.
            val acceptedAsNew = previousPeer == null || (!previousPeer.isActive && peerConnections.replace(key, previousPeer, ctx.channel()))
            if (acceptedAsNew) {
              if (previousPeer != null) {
                log.info(s"${id(ctx)} Replacing dead connection to peer with nonce ${remoteHandshake.nodeNonce} (stale channel ${id(previousPeer)} was inactive)")
                establishedConnections.remove(previousPeer)
                allChannels.remove(previousPeer)
              }
              log.info(s"${id(ctx)} Accepted handshake $remoteHandshake")
              removeHandshakeHandlers(ctx, this)
              establishedConnections.put(ctx.channel(), peerInfo(remoteHandshake, ctx.channel()))

              ctx.channel().attr(NodeNameAttributeKey).set(remoteHandshake.nodeName)
              ctx.channel().attr(NodeVersionAttributeKey).set(remoteHandshake.applicationVersion)

              Option(ctx.channel().attr(ConnectionStartAttributeKey).get()).foreach { start =>
                log.trace(s"Time taken to accept handshake = ${System.nanoTime() / 1000 - start} ms")
              }
              ctx.channel().closeFuture().addListener { (f: ChannelFuture) =>
                peerConnections.remove(key, f.channel())
                establishedConnections.remove(f.channel())
                log.trace(s"${id(f.channel())} was closed")
              }

              connectionNegotiated(ctx)
              ctx.fireChannelRead(msg)
            } else {
              log.info(
                s"${id(ctx)} Already connected to peer with nonce ${remoteHandshake.nodeNonce} on live channel ${id(previousPeer)} -- closing this duplicate"
              )
              suspendAndClose(
                s"${id(ctx)} Already connected to peer with nonce ${remoteHandshake.nodeNonce} on channel ${id(previousPeer)}",
                verifiedDeclaredAddress,
                ctx
              )
            }
        }
      }
    case _ => super.channelRead(ctx, msg)
  }

  protected def connectionNegotiated(ctx: ChannelHandlerContext): Unit = {
    ctx.channel().closeFuture().addListener((_: ChannelFuture) => allChannels.remove(ctx.channel()))
    allChannels.add(ctx.channel())
  }

  protected def sendLocalHandshake(ctx: ChannelHandlerContext): Unit = {
    ctx.writeAndFlush(localHandshake.encode(ctx.alloc().buffer()))
  }
}

object HandshakeHandler {

  val NodeNameAttributeKey: AttributeKey[String]                       = AttributeKey.newInstance[String]("name")
  val NodeVersionAttributeKey: AttributeKey[(Int, Int, Int)]           = AttributeKey.newInstance[(Int, Int, Int)]("version")
  val NodeDeclaredAddressAttributeKey: AttributeKey[InetSocketAddress] = AttributeKey.newInstance[InetSocketAddress]("declaredAddress")

  private val ConnectionStartAttributeKey = AttributeKey.newInstance[Long]("connectionStart")

  def versionIsSupported(remoteVersion: (Int, Int, Int)): Boolean =
    remoteVersion._1 >= 0 // accept any version — we control all nodes in this network

  def removeHandshakeHandlers(ctx: ChannelHandlerContext, thisHandler: ChannelHandler): Unit = {
    ctx.pipeline().remove(classOf[HandshakeTimeoutHandler])
    ctx.pipeline().remove(thisHandler)
  }

  def peerInfo(remoteHandshake: Handshake, channel: Channel): PeerInfo =
    PeerInfo(
      channel.remoteAddress(),
      remoteHandshake.declaredAddress,
      remoteHandshake.applicationName,
      remoteHandshake.applicationVersion,
      remoteHandshake.nodeName,
      remoteHandshake.nodeNonce
    )

  @Sharable
  class Server(
      handshake: Handshake,
      establishedConnections: ConcurrentMap[Channel, PeerInfo],
      peerConnections: ConcurrentMap[PeerKey, Channel],
      peerDatabase: PeerDatabase,
      allChannels: ChannelGroup
  ) extends HandshakeHandler(handshake, establishedConnections, peerConnections, peerDatabase, allChannels) {
    override protected def connectionNegotiated(ctx: ChannelHandlerContext): Unit = {
      sendLocalHandshake(ctx)
      super.connectionNegotiated(ctx)
    }
  }

  @Sharable
  class Client(
      handshake: Handshake,
      establishedConnections: ConcurrentMap[Channel, PeerInfo],
      peerConnections: ConcurrentMap[PeerKey, Channel],
      peerDatabase: PeerDatabase,
      allChannels: ChannelGroup
  ) extends HandshakeHandler(handshake, establishedConnections, peerConnections, peerDatabase, allChannels) {
    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      sendLocalHandshake(ctx)
      ctx.channel().attr(ConnectionStartAttributeKey).set(System.nanoTime() / 1000)
      super.channelActive(ctx)
    }
  }
}
