package com.decentralchain.network

import com.decentralchain.state.EndorsementStorage
import com.decentralchain.utils.{SafeHandler, ScorexLogging}
import io.netty.channel.Channel

/** The EndorseBlock intake handler, extracted from Application so the subscription-survival
  * property is unit-testable. Malformed wire input is already rejected at decode time
  * (`EndorseBlock.fromProtobuf`, MessageCodec closes the channel); this guards everything after it.
  */
object EndorsementIngress extends ScorexLogging {
  def handler(
      storage: EndorsementStorage,
      broadcast: (EndorseBlock, Channel) => Unit,
      onFailure: (Channel, String) => Unit
  ): ((Channel, EndorseBlock)) => Unit =
    SafeHandler[(Channel, EndorseBlock)](
      "endorse-block",
      { case ((ch, x), e) => onFailure(ch, s"EndorseBlock handler failed for $x: ${e.getMessage}") }
    ) { case (ch, x) =>
      storage.tryAdd(x) match {
        case Left(err)   => log.trace(s"Unexpected $x: $err")
        case Right(true) => broadcast(x, ch)
        case _           =>
      }
    }
}
