package com.decentralchain.api.http

import java.time.Instant

import org.apache.pekko.http.scaladsl.server.Route
import com.decentralchain.Shutdownable
import com.decentralchain.consensus.hotstuff.{HotStuffEquivocationObservation, HotStuffObservation}
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
    // `hotStuffEquivocationsTotal` is included only when at least one verified equivocation has been
    // observed — /node/status is unchanged (byte-for-byte) when zero, same convention as `hotStuff`
    // above. Feeds the already-deployed exporter metric + critical alert (`dcc_hotstuff_equivocations_total`).
    val hotStuffEquivocations =
      if (HotStuffEquivocationObservation.totalCount > 0) Json.obj("hotStuffEquivocationsTotal" -> HotStuffEquivocationObservation.totalCount)
      else Json.obj()
    complete(
      Json.obj(
        "blockchainHeight"       -> blockchain.height,
        "stateHeight"            -> blockchain.height,
        "updatedTimestamp"       -> lastUpdated,
        "updatedDate"            -> Instant.ofEpochMilli(lastUpdated).toString,
        "generationPeriodLength" -> blockchain.settings.functionalitySettings.generationPeriodLength
      ) ++ hotStuff ++ hotStuffEquivocations
    )
  }
}
