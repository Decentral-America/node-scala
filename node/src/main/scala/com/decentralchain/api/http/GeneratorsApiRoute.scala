package com.decentralchain.api.http

import com.decentralchain.api.common.CommonGeneratorsApi
import com.decentralchain.settings.RestAPISettings
import com.decentralchain.state.{Blockchain, Height}
import com.decentralchain.utils.Time
import monix.eval.Task
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Accept
import org.apache.pekko.http.scaladsl.server.Route
import play.api.libs.json.{JsNumber, JsString, Json}

case class GeneratorsApiRoute(settings: RestAPISettings, blockchain: Blockchain, api: CommonGeneratorsApi, time: Time, routeTimeout: RouteTimeout)
    extends ApiRoute {
  override lazy val route: Route = (path("generators" / "at" / IntNumber) & get & optionalHeaderValueByType(Accept)) { (height, accept) =>
    if (height > blockchain.height) complete(StatusCodes.NotFound, Json.arr())
    else
      routeTimeout.executeToFuture {
        Task {
          val formatNumbersAsStrings = accept.fold(false) {
            case a if a.mediaRanges.exists(CustomJson.acceptsNumbersAsStrings) => true
            case _                                                             => false
          }

          api.generators(Height(height)).map { x =>
            val balance = if (formatNumbersAsStrings) JsString(x.balance.toString) else JsNumber(x.balance)

            val builder = Json.newBuilder
            builder ++= Seq(
              "address"       -> x.address.toString,
              "balance"       -> balance,
              "transactionId" -> x.commitTxnId.toString
            )

            x.conflictHeight.foreach { h =>
              builder += "conflictHeight" -> h.toInt
            }

            builder.result()
          }
        }
      }
  }
}
