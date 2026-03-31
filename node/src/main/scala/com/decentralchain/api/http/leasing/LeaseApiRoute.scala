package com.decentralchain.api.http.leasing

import org.apache.pekko.http.scaladsl.server.Route
import com.decentralchain.api.common.{CommonAccountsApi, LeaseInfo}
import com.decentralchain.api.http.*
import com.decentralchain.api.http.ApiError.{InvalidIds, TransactionDoesNotExist}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.network.TransactionPublisher
import com.decentralchain.settings.RestAPISettings
import com.decentralchain.state.Blockchain
import com.decentralchain.utils.Time
import com.decentralchain.wallet.Wallet
import play.api.libs.json.JsonConfiguration.Aux
import play.api.libs.json.*

case class LeaseApiRoute(
    settings: RestAPISettings,
    wallet: Wallet,
    blockchain: Blockchain,
    transactionPublisher: TransactionPublisher,
    time: Time,
    commonAccountApi: CommonAccountsApi,
    routeTimeout: RouteTimeout
) extends ApiRoute
    with AuthRoute {
  import LeaseApiRoute.*

  override val route: Route = pathPrefix("leasing") {
    active ~ leaseInfo
  }

  private def active: Route = (pathPrefix("active") & get) {
    path(AddrSegment) { address =>
      routeTimeout.executeToFuture(
        commonAccountApi.activeLeases(address).map(Json.toJson(_)).toListL
      )
    }
  }

  private def leaseInfo: Route = pathPrefix("info") {
    (get & path(TransactionId)) { leaseId =>
      val result = commonAccountApi
        .leaseInfo(leaseId())
        .toRight(TransactionDoesNotExist)

      complete(result)
    } ~ anyParam("id", limit = settings.transactionsByAddressLimit) { ids =>
      leasingInfosMap(ids) match {
        case Left(err) => complete(err)
        case Right(leaseInfoByIdMap) =>
          val results = ids.map(leaseInfoByIdMap).toVector
          complete(results)
      }
    }
  }

  private def leasingInfosMap(ids: Iterable[String]): Either[InvalidIds, Map[String, LeaseInfo]] = {
    val infos = ids.map(id =>
      (for {
        id <- Base58.tryDecodeWithLimit(id).toOption
        li <- commonAccountApi.leaseInfo(ByteStr(id))
      } yield li).toRight(id)
    )
    val failed = infos.flatMap(_.left.toOption)

    if (failed.isEmpty) {
      Right(infos.collect { case Right(li) =>
        li.id.toString -> li
      }.toMap)
    } else {
      Left(InvalidIds(failed.toVector))
    }
  }
}

object LeaseApiRoute {
  implicit val leaseStatusWrites: Writes[LeaseInfo.Status] =
    Writes(s => JsString(s.toString.toLowerCase))

  implicit val config: Aux[Json.MacroOptions] = JsonConfiguration(optionHandlers = OptionHandlers.WritesNull)

  implicit val leaseInfoWrites: OWrites[LeaseInfo] = {
    import com.decentralchain.utils.byteStrFormat
    Json.writes[LeaseInfo]
  }
}
