package com.decentralchain.consensus

import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.assets.exchange.ExchangeTransaction
import com.decentralchain.transaction.smart.InvokeScriptTransaction
import com.decentralchain.transaction.{Authorized, Transaction}

object TransactionsOrdering {
  trait DccOrdering extends Ordering[Transaction] {
    def isWhitelisted(t: Transaction): Boolean = false
    def transactionSize(tx: Transaction): Int  = tx.bytesSize
    def txTimestampOrder(ts: Long): Long
    private def orderBy(t: Transaction): (Boolean, Double, Long, Long) = {
      val byWhiteList = !isWhitelisted(t) // false < true
      val size        = transactionSize(t)
      val byFee       = if (t.assetFee._1 != Dcc) 0 else -t.assetFee._2
      val byTimestamp = txTimestampOrder(t.timestamp)

      (byWhiteList, byFee.toDouble / size.toDouble, byFee, byTimestamp)
    }
    override def compare(first: Transaction, second: Transaction): Int = {
      import Ordering.Double.TotalOrdering
      implicitly[Ordering[(Boolean, Double, Long, Long)]].compare(orderBy(first), orderBy(second))
    }
  }

  object InBlock extends DccOrdering {
    // sorting from network start
    override def txTimestampOrder(ts: Long): Long = -ts
  }

  case class InUTXPool(whitelistAddresses: Set[String]) extends DccOrdering {

    override def transactionSize(tx: Transaction): Int = tx match {
      case _: ExchangeTransaction => 676 // order v3 with matcher fee in custom assets, tx V2
      case _                      => super.transactionSize(tx)
    }

    override def isWhitelisted(t: Transaction): Boolean =
      t match {
        case _ if whitelistAddresses.isEmpty                                            => false
        case a: Authorized if whitelistAddresses.contains(a.sender.toAddress.toString)  => true
        case i: InvokeScriptTransaction if whitelistAddresses.contains(i.dApp.toString) => true
        case _                                                                          => false
      }
    override def txTimestampOrder(ts: Long): Long = ts
  }
}
