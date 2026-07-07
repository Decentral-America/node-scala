package com.decentralchain.network

import java.io.IOException

import com.decentralchain.utils.{ScorexLogging, forceStopApplication}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}

import scala.util.control.NonFatal
@Sharable
class FatalErrorHandler extends ChannelInboundHandlerAdapter with ScorexLogging {
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = cause match {
    case ioe: IOException if ioe.getMessage == "Connection reset by peer" =>
      // https://stackoverflow.com/q/9829531
      // https://stackoverflow.com/q/1434451
      // Bumped from trace to info (INCIDENT-GEN0-PEERS.md #11, 2026-07-07) --
      // was invisible at every log level actually tested; this is the exact
      // exception a raw TCP RST surfaces as.
      log.info(s"${id(ctx)} Connection reset by peer")
    case NonFatal(_) =>
      log.info(s"${id(ctx)} Exception caught", cause)
    case _ =>
      new Thread(
        () => {
          log.error(s"${id(ctx)} Fatal error in channel, terminating application", cause)
          forceStopApplication()
        },
        "dcc-platform-shutdown-thread"
      ).start()
  }
}
