package com.decentralchain.network

import com.decentralchain.account.Address
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.FinalizationVoting
import com.decentralchain.common.state.ByteStr
import com.decentralchain.state.{EndorsementFilter, EndorsementStorage, Height}
import com.decentralchain.test.FreeSpec
import io.netty.channel.Channel
import io.netty.channel.local.LocalChannel
import monix.execution.Scheduler
import monix.reactive.Observable

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.*

class EndorsementIngressSpec extends FreeSpec {
  private val good = EndorseBlock(1, ByteStr(Array.fill[Byte](32)(1)), Height(5), ByteStr(Array.fill[Byte](32)(2)), ByteStr(Array.fill[Byte](96)(3)))
  private val bad  = good.copy(endorserIndex = -2)

  /** Throws for a negative index exactly like the pre-fix `GeneratorIndex.checked` did. */
  private val throwingStorage = new EndorsementStorage {
    def tryAdd(msg: EndorseBlock): Either[String, Boolean] = {
      require(msg.endorserIndex >= -1, s"Wrong generator index: ${msg.endorserIndex}. Expected -1 or >= 0")
      Right(true)
    }
    def startVoting(filter: EndorsementFilter): Boolean                                 = false
    def tryCollectAndClear(endorsedId: BlockId): Option[FinalizationVoting]             = None
    def tryCollectFor(endorsedId: BlockId, forger: Address): Option[FinalizationVoting] = None
  }

  "a message that makes the handler throw does not end the subscription: the next valid message is still processed" in {
    val broadcasted = mutable.ListBuffer.empty[EndorseBlock]
    val failures    = mutable.ListBuffer.empty[String]
    val ch: Channel = new LocalChannel()
    val handler     = EndorsementIngress.handler(throwingStorage, (x, _) => broadcasted += x, (_, reason) => failures += reason)
    val done        = Observable(ch -> bad, ch -> good).foreach(handler)(using Scheduler.global)
    Await.result(done, 10.seconds)
    broadcasted.toList shouldBe List(good)
    failures should have size 1
  }

  "without SafeHandler the same throwing handler stops the stream (documents the Monix behaviour being guarded)" in {
    val seen = mutable.ListBuffer.empty[Int]
    val done = Observable(-2, 1).foreach { x => require(x >= 0); seen += x }(using Scheduler.global)
    a[IllegalArgumentException] should be thrownBy Await.result(done, 10.seconds)
    seen shouldBe empty
  }
}
