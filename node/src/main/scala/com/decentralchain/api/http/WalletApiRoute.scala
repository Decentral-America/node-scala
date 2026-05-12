package com.decentralchain.api.http

import org.apache.pekko.http.scaladsl.server.Route
import com.decentralchain.common.utils.Base58
import com.decentralchain.settings.RestAPISettings
import com.decentralchain.wallet.Wallet
import play.api.libs.json.Json

case class WalletApiRoute(settings: RestAPISettings, wallet: Wallet) extends ApiRoute with AuthRoute {
  override lazy val route: Route = seed

  def seed: Route = (path("wallet" / "seed") & get & withAuth) {
    complete(Json.obj("seed" -> Base58.encode(wallet.seed)))
  }
}
