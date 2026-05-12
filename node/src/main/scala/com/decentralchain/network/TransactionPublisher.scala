package com.decentralchain.network

import scala.concurrent.{ExecutionException, Future}
import scala.util.Success

import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.Transaction
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.smart.script.trace.TracedResult
import com.decentralchain.utils.Schedulers.ExecutorExt
import com.decentralchain.utils.ScorexLogging
import io.netty.channel.Channel
import monix.execution.Scheduler

trait TransactionPublisher {
  def validateAndBroadcast(tx: Transaction, source: Option[Channel]): Future[TracedResult[ValidationError, Boolean]]
}

object TransactionPublisher extends ScorexLogging {

  import Scheduler.Implicits.global

  def timeBounded(
      putIfNew: (Transaction, Boolean) => TracedResult[ValidationError, Boolean],
      broadcast: (Transaction, Option[Channel]) => Unit,
      timedScheduler: Scheduler,
      allowRebroadcast: Boolean,
      canBroadcast: () => Either[ValidationError, Unit]
  ): TransactionPublisher = { (tx, source) =>
    canBroadcast() match {
      case Right(_) =>
        timedScheduler
          .executeCatchingInterruptedException(putIfNew(tx, source.isEmpty))
          .recover {
            case err: ExecutionException if err.getCause.isInstanceOf[InterruptedException] =>
              log.trace(s"Transaction took too long to validate: ${tx.id()}")
              TracedResult(Left(GenericError("Transaction took too long to validate")))
            case err =>
              log.warn(s"Error validating transaction ${tx.id()}", err)
              TracedResult(Left(GenericError(err)))
          }
          .andThen {
            case Success(TracedResult(Right(isNew), _, _)) if isNew || (allowRebroadcast && source.isEmpty) => broadcast(tx, source)
          }

      case Left(err) =>
        Future.successful(TracedResult.wrapE(Left(err)))
    }
  }
}
