package com.decentralchain.http

import com.decentralchain.lang.ValidationError
import com.decentralchain.network.TransactionPublisher
import com.decentralchain.transaction.Transaction
import com.decentralchain.transaction.smart.script.trace.TracedResult

import scala.concurrent.Future

object DummyTransactionPublisher {
  val accepting: TransactionPublisher = { (_, _) =>
    Future.successful(TracedResult(Right(true)))
  }

  def rejecting(error: Transaction => ValidationError): TransactionPublisher = { (tx, _) =>
    Future.successful(TracedResult(Left(error(tx))))
  }
}
