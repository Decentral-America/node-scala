package com.decentralchain.api.http.alias

import org.apache.pekko.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import org.apache.pekko.http.scaladsl.server.Route
import cats.syntax.either.*
import com.decentralchain.account.Alias
import com.decentralchain.api.common.CommonTransactionsApi
import com.decentralchain.api.http.*
import com.decentralchain.network.TransactionPublisher
import com.decentralchain.settings.RestAPISettings
import com.decentralchain.state.Blockchain
import com.decentralchain.transaction.*
import com.decentralchain.utils.Time
import com.decentralchain.wallet.Wallet
import play.api.libs.json.{JsString, Json}

case class AliasApiRoute(
    settings: RestAPISettings,
    commonApi: CommonTransactionsApi,
    wallet: Wallet,
    transactionPublisher: TransactionPublisher,
    time: Time,
    blockchain: Blockchain,
    routeTimeout: RouteTimeout
) extends ApiRoute
    with AuthRoute {

  override val route: Route = pathPrefix("alias") {
    addressOfAlias ~ aliasOfAddress
  }

  def addressOfAlias: Route = (get & path("by-alias" / Segment)) { aliasName =>
    complete {
      Alias
        .create(aliasName)
        .flatMap { a =>
          blockchain.resolveAlias(a).bimap(_ => TxValidationError.AliasDoesNotExist(a), addr => Json.obj("address" -> addr.toString))
        }
    }
  }

  private implicit val ess: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  def aliasOfAddress: Route = (get & path("by-address" / AddrSegment)) { address =>
    routeTimeout.executeFromObservable {
      commonApi
        .aliasesOfAddress(address)
        .map { case (_, tx) => JsString(tx.alias.toString) }
    }
  }
}
