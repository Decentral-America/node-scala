package com.decentralchain.api.http

import java.time.Instant

import org.apache.pekko.http.scaladsl.server.Route
import com.decentralchain.Shutdownable
import com.decentralchain.consensus.hotstuff.HotStuffObservation
import com.decentralchain.settings.{Constants, RestAPISettings}
import com.decentralchain.state.Blockchain
import com.decentralchain.utils.ScorexLogging
import play.api.libs.json.Json

case class NodeApiRoute(settings: RestAPISettings, blockchain: Blockchain, application: Shutdownable)
    extends ApiRoute
    with AuthRoute
    with ScorexLogging {

  override lazy val route: Route = pathPrefix("node") {
    stop ~ status ~ version
  }

  def version: Route = (get & path("version")) {
    complete(Json.obj("version" -> Constants.AgentName))
  }

  def stop: Route = (post & path("stop") & withAuth) {
    log.info("Request to stop application")
    application.shutdown()
    complete(Json.obj("stopped" -> true))
  }

  def status: Route = (get & path("status")) {
    val lastUpdated = blockchain.lastBlockHeader.get.header.timestamp
    // `hotStuffFinalizedHeight` is included only when the (observational) HotStuff coordinator is
    // enabled and has committed at least one block — /node/status is unchanged when HotStuff is off.
    val hotStuff = HotStuffObservation.committedHeightOpt.fold(Json.obj())(h => Json.obj("hotStuffFinalizedHeight" -> h))
    complete(
      Json.obj(
        "blockchainHeight"       -> blockchain.height,
        "stateHeight"            -> blockchain.height,
        "updatedTimestamp"       -> lastUpdated,
        "updatedDate"            -> Instant.ofEpochMilli(lastUpdated).toString,
        "generationPeriodLength" -> blockchain.settings.functionalitySettings.generationPeriodLength
      ) ++ hotStuff
    )
  }
}
