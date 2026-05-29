package com.decentralchain.api.http

import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.MediaTypes.`application/json`
import org.apache.pekko.http.scaladsl.model.headers.Accept
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import com.decentralchain.api.http.assets.AssetsApiRoute
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.http.{ApiErrorMatchers, DummyTransactionPublisher, RestAPISettingsHelper}
import com.decentralchain.settings.DCCSettings
import com.decentralchain.test.*
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.utils.SharedSchedulerMixin
import org.scalactic.source.Position
import play.api.libs.json.*

import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

class CustomJsonMarshallerSpec
    extends PropSpec
    with RestAPISettingsHelper
    with ScalatestRouteTest
    with ApiErrorMatchers
    with ApiMarshallers
    with SharedDomain
    with SharedSchedulerMixin {

  private val numberFormat = Accept(`application/json`.withParams(Map("large-significand-format" -> "string")))
  private val richAccount  = TxHelpers.signer(55)

  override def genesisBalances: Seq[AddrWithBalance] = Seq(AddrWithBalance(richAccount.toAddress, 50000.dcc))
  override def settings: DCCSettings                 = DomainPresets.BlockRewardDistribution

  private def ensureFieldsAre[A: ClassTag](v: JsObject, fields: String*)(implicit pos: Position): Unit =
    for (f <- fields) (v \ f).get shouldBe a[A]

  private def checkRoute(req: HttpRequest, route: Route, fields: String*)(implicit pos: Position): Unit = {
    req ~> route ~> check {
      ensureFieldsAre[JsNumber](responseAs[JsObject], fields*)
    }

    req ~> numberFormat ~> route ~> check {
      ensureFieldsAre[JsString](responseAs[JsObject], fields*)
    }
  }

  private val transactionsRoute =
    TransactionsApiRoute(
      restAPISettings,
      domain.transactionsApi,
      domain.wallet,
      domain.blockchain,
      () => domain.blockchain,
      () => domain.utxPool.size,
      DummyTransactionPublisher.accepting,
      ntpTime,
      new RouteTimeout(60.seconds)(using sharedScheduler)
    ).route

  property("/transactions/info/{id}") {
    // NOTE: Additional transaction types could improve coverage
    val leaseTx = TxHelpers.lease(sender = richAccount, TxHelpers.address(80), 25.dcc)
    domain.appendBlock(leaseTx)
    checkRoute(Get(s"/transactions/info/${leaseTx.id()}"), transactionsRoute, "amount")
  }

  property("/transactions/calculateFee") {
    val tx = TxHelpers.transfer(richAccount, TxHelpers.address(81), 5.dcc)
    checkRoute(Post("/transactions/calculateFee", tx.json()), transactionsRoute, "feeAmount")
  }

  private val rewardRoute = RewardApiRoute(domain.blockchain).route

  property("/blockchain/rewards") {
    checkRoute(Get("/blockchain/rewards/2"), rewardRoute, "totalDccAmount", "currentReward", "minIncrement")
  }

  property("/debug/stateDcc") {
    pending // NOTE: Blocked — distributions/portfolio endpoints not mockable in test harness
  }

  private val assetsRoute = AssetsApiRoute(
    restAPISettings,
    60.seconds,
    domain.wallet,
    domain.blockchain,
    () => domain.blockchain,
    ntpTime,
    domain.accountsApi,
    domain.assetsApi,
    1000,
    new RouteTimeout(60.seconds)(using sharedScheduler)
  ).route

  property("/assets/{assetId}/distribution/{height}/limit/{limit}") {
    pending // NOTE: Blocked — distributions/portfolio endpoints not mockable in test harness
  }

  property("/assets/balance/{address}/{assetId}") {
    val issue = TxHelpers.issue(richAccount, 100000_00, 2.toByte)
    domain.appendBlock(issue)
    checkRoute(Get(s"/assets/balance/${richAccount.toAddress}/${issue.id()}"), assetsRoute, "balance")
  }
}
