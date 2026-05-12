package com.decentralchain.api.http

import org.apache.pekko.http.scaladsl.marshalling.{ToResponseMarshallable, ToResponseMarshaller}
import org.apache.pekko.http.scaladsl.server.{Directive1, Route}
import com.decentralchain.lang.ValidationError
import com.decentralchain.network.TransactionPublisher
import com.decentralchain.transaction.Transaction
import com.decentralchain.transaction.smart.script.trace.TracedResult
import play.api.libs.json.*

import scala.concurrent.Future

trait BroadcastRoute { apiRoute: ApiRoute =>
  def transactionPublisher: TransactionPublisher

  private def broadcastTransaction(tx: Transaction, includeTrace: Boolean): Future[ToResponseMarshallable] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val trw: ToResponseMarshaller[TracedResult[ApiError, Transaction]] = tracedResultMarshaller(includeTrace)
    transactionPublisher.validateAndBroadcast(tx, None).map(_.leftMap(ApiError.fromValidationError).map(_ => tx))
  }

  private def extractTraceParameter(tx: Transaction): Directive1[ToResponseMarshallable] =
    parameter("trace".as[Boolean].?(false))
      .flatMap { includeTrace =>
        provide(broadcastTransaction(tx, includeTrace))
      }

  def broadcast[A: Reads](f: A => Either[ValidationError, Transaction]): Route = {
    val directive = jsonPostD[A].flatMap { a =>
      f(a).fold(
        e => provide[ToResponseMarshallable](ApiError.fromValidationError(e)),
        extractTraceParameter
      )
    }
    directive(complete(_))
  }
}
