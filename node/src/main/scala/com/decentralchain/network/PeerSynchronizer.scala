package com.decentralchain.network

import com.decentralchain.utils.ScorexLogging
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

class PeerSynchronizer(peerDatabase: PeerDatabase, peerRequestInterval: FiniteDuration) extends ChannelInboundHandlerAdapter with ScorexLogging {
  private var peersRequested     = false
  private var lastPeersServedAt  = 0L

  private def requestPeers(ctx: ChannelHandlerContext): Unit = if (ctx.channel().isActive) {
    peersRequested = true
    ctx.writeAndFlush(GetPeers)

    ctx.executor().schedule(peerRequestInterval) {
      requestPeers(ctx)
    }
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
    Option(ctx.channel().attr(HandshakeHandler.NodeDeclaredAddressAttributeKey).get()).foreach(peerDatabase.touch)
    msg match {
      case _: Handshake =>
        requestPeers(ctx)
        super.channelRead(ctx, msg)
      case GetPeers =>
        // Rate-limit responses (a peer can spam GetPeers as a cheap amplification vector) and truncate the
        // reply to the wire cap, else an oversized frame would be rejected by the receiver.
        val now = System.currentTimeMillis()
        if (now - lastPeersServedAt >= peerRequestInterval.toMillis) {
          lastPeersServedAt = now
          val peers  = peerDatabase.knownPeers.keys.toSeq
          val capped = if (peers.size > PeersSpec.MaxAddresses) Random.shuffle(peers).take(PeersSpec.MaxAddresses) else peers
          ctx.writeAndFlush(KnownPeers(capped))
        }
      case KnownPeers(peers) if peersRequested =>
        peersRequested = false
        val (added, notAdded) = peers.partition(peerDatabase.addCandidate)
        log.trace(s"${id(ctx)} Added peers: ${format(added)}, not added peers: ${format(notAdded)}")
      case KnownPeers(peers) =>
        log.trace(s"${id(ctx)} Got unexpected list of known peers containing ${peers.size} entries")
      case _ =>
        super.channelRead(ctx, msg)
    }
  }

  private def format[T](xs: Iterable[T]): String = xs.mkString("[", ", ", "]")
}

object PeerSynchronizer {

  @Sharable
  class NoopPeerSynchronizer extends ChannelInboundHandlerAdapter {

    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
      msg match {
        case GetPeers      =>
        case KnownPeers(_) =>
        case _ =>
          super.channelRead(ctx, msg)
      }
    }
  }

  val Disabled = new NoopPeerSynchronizer()

}
