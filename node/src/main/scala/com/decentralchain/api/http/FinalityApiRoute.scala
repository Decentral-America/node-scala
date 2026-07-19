package com.decentralchain.api.http

import com.decentralchain.api.common.CommonGeneratorsApi.GeneratorEntry
import com.decentralchain.api.common.{CommonBlocksApi, CommonGeneratorsApi}
import com.decentralchain.consensus.hotstuff.HotStuffFinalityTracker
import com.decentralchain.state.{Blockchain, GenerationPeriod, Height}
import org.apache.pekko.http.scaladsl.server.Route
import play.api.libs.json.*

case class FinalityApiRoute(
    blockchain: Blockchain,
    blocksApi: CommonBlocksApi,
    generatorsApi: CommonGeneratorsApi,
    hotStuffTracker: HotStuffFinalityTracker
) extends ApiRoute {
  import FinalityApiRoute.given

  override def route: Route = pathPrefix("blockchain" / "finality") {
    (get & pathEndOrSingleSlash) {
      complete(finalityInfo)
    }
  }

  private def finalityInfo: JsObject = {
    val currentHeight   = Height(blockchain.height)
    val currentPeriod   = blockchain.generationPeriodOf(currentHeight)
    // `finalizedHeight` (feature-25 DeterministicFinality) is AUTHORITATIVE — it gates reversion.
    // `hotStuffFinalizedHeight`/Block is an ADVISORY overlay signal: it does not gate block application,
    // fork-choice, or rollback, and the overlay lacks a full BFT lock rule (pending external audit). Only
    // report the HotStuff block if it is still on THIS node's canonical chain — after a reorg the recorded
    // advisory block can be orphaned; never serve an orphaned block as "finalized".
    val hotStuffLatest = hotStuffTracker.latestFinalizedBlock.filter(fb => blockchain.heightOf(fb.blockId).map(Height(_)).contains(fb.height))
    Json.obj(
      "height"                  -> currentHeight,
      "finalizedHeight"         -> blocksApi.currentFinalizedHeight,
      "hotStuffFinalizedHeight" -> hotStuffLatest.map(_.height),
      "hotStuffFinalizedBlock"  -> hotStuffLatest.map(_.blockId.toString),
      "hotStuffFinalityIsAdvisory" -> true,
      "currentGenerationPeriod" -> currentPeriod,
      "currentGenerators"       -> generatorsApi.generators(currentHeight),
      "nextGenerationPeriod"    -> currentPeriod.map(_.next),
      "nextGenerators" -> currentPeriod.fold(Seq.empty)(p =>
        generatorsApi
          .generators(p.next.start)
          .map(ge =>
            Json.obj(
              "address"       -> ge.address,
              "transactionId" -> ge.commitTxnId
            )
          )
      )
    )
  }
}

object FinalityApiRoute {
  given Writes[GenerationPeriod] = (gp: GenerationPeriod) =>
    Json.obj(
      "start" -> gp.start,
      "end"   -> gp.end
    )

  given Writes[GeneratorEntry] = (ge: GeneratorEntry) =>
    Json.obj(
      "address"        -> ge.address,
      "transactionId"  -> ge.commitTxnId,
      "balance"        -> ge.balance,
      "conflictHeight" -> ge.conflictHeight
    )
}
