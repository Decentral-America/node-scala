package com.decentralchain.network
import java.util.concurrent.CountDownLatch

import com.decentralchain.account.PublicKey
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.lang.ValidationError
import com.decentralchain.test.FreeSpec
import com.decentralchain.transaction.smart.script.trace.TracedResult
import com.decentralchain.transaction.{GenesisTransaction, Transaction}
import com.decentralchain.utils.Schedulers
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.HashedWheelTimer
import monix.execution.atomic.AtomicInt
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration.*

class TimedTransactionPublisherSpec extends FreeSpec with BeforeAndAfterAll with Eventually {
  private val timer     = new HashedWheelTimer
  private val scheduler = Schedulers.timeBoundedFixedPool(timer, 1.second, 1, "test-utx-sync")

  "UtxPoolSynchronizer" - {
    val latch   = new CountDownLatch(5)
    val counter = AtomicInt(10)

    def countTransactions(): TracedResult[ValidationError, Boolean] = {
      // the first 5 transactions will take too long to validate
      if (counter.getAndDecrement() > 5) {
        while (!Thread.currentThread().isInterrupted) {
          Thread.sleep(100)
        }
      }

      latch.countDown()

      TracedResult(Right(true))
    }

    "accepts only those transactions from network which can be validated quickly" in withUPS(_ => countTransactions()) { ups =>
      1 to 10 foreach { i =>
        ups.validateAndBroadcast(
          GenesisTransaction.create(PublicKey(new Array[Byte](32)).toAddress, i * 10L, 0L).explicitGet(),
          Some(new EmbeddedChannel)
        )
      }
      latch.await()               // 5 transactions have completed validation process
      counter.get() shouldEqual 0 // all 10 transactions have been processed
    }
  }

  "hands network-received rejections, and only those, to onNetworkRejected" in {
    val rejected  = new java.util.concurrent.ConcurrentLinkedQueue[Option[io.netty.channel.Channel]]()
    val publisher = TransactionPublisher.timeBounded(
      (_, _) => TracedResult(Left(com.decentralchain.transaction.TxValidationError.GenericError("not yet"))),
      (_, _) => (),
      scheduler,
      allowRebroadcast = false,
      () => Right(()),
      onNetworkRejected = (_, source, _) => rejected.add(source)
    )
    val tx      = GenesisTransaction.create(PublicKey(new Array[Byte](32)).toAddress, 1L, 0L).explicitGet()
    val channel = new EmbeddedChannel
    scala.concurrent.Await.ready(publisher.validateAndBroadcast(tx, Some(channel)), 5.seconds)
    scala.concurrent.Await.ready(publisher.validateAndBroadcast(tx, None), 5.seconds) // API path
    eventually(rejected.size() should be >= 1)
    Thread.sleep(300) // the andThen callbacks run after the futures complete; give a wrong API-path call time to land
    rejected.size() shouldBe 1
    rejected.peek() shouldBe Some(channel)
  }

  private def withUPS(putIfNew: Transaction => TracedResult[ValidationError, Boolean])(f: TransactionPublisher => Unit): Unit =
    f(TransactionPublisher.timeBounded((tx, _) => putIfNew(tx), (_, _) => (), scheduler, allowRebroadcast = false, () => Right(())))

  override protected def afterAll(): Unit = {
    super.afterAll()
    scheduler.shutdown()
    timer.stop()
  }
}
