package com.decentralchain.network
import cats.kernel.Eq
import com.decentralchain.common.state.ByteStr
import com.decentralchain.db.WithDomain
import com.decentralchain.settings.SynchronizationSettings.UtxSynchronizerSettings
import com.decentralchain.test.DomainPresets.RideV6
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.TxHelpers.transfer
import com.decentralchain.transaction.smart.script.trace.TracedResult
import com.decentralchain.utils.Schedulers
import monix.execution.atomic.AtomicInt
import monix.reactive.Observable

import scala.concurrent.Future

class TransactionSynchronizerSpec extends PropSpec with WithDomain {
  property("synchronizer should broadcast transactions on new both microblocks and blocks") {
    withDomain(RideV6) { d =>
      val blockIds =
        Observable
          .repeatEval(d.blockchain.lastBlockId.getOrElse(ByteStr.empty))
          .distinctUntilChanged(using Eq.fromUniversalEquals)

      val tx  = transfer()
      val txs = Observable.repeatEval(tx)

      val broadcastCount = AtomicInt(0)

      val scheduler    = Schedulers.fixedPool(4, "synchronizer")
      val synchronizer = TransactionSynchronizer(
        UtxSynchronizerSettings(1000000, 8, 5000, true),
        blockIds,
        txs.map((null, _)),
        (_, _) => Future.successful { broadcastCount.increment(); TracedResult(Right(true)) }
      )(using scheduler)

      val appends = 20
      (1 to appends).foreach { i =>
        if (i % 2 == 1)
          d.appendBlock()
        else
          d.appendMicroBlock(transfer())
        while (broadcastCount.get() != i + 1)
          Thread.sleep(10)
      }

      broadcastCount.get() shouldBe appends + 1

      synchronizer.cancel()
      scheduler.shutdown()
    }
  }
}
