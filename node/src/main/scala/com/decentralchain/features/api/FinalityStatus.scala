package com.decentralchain.features.api

import com.decentralchain.state.{GenerationPeriod, Height}
import play.api.libs.json.*
import play.api.libs.functional.syntax._

case class FinalityStatus(
    height: Height,
    finalizedHeight: Height,
    currentGenerationPeriod: Option[GenerationPeriod],
    nextGenerationPeriod: Option[GenerationPeriod]
)

object FinalityStatus {
  def parse(activationHeight: Option[Height]): Reads[FinalityStatus] =
    Reads { json =>
      for {
        height                  <- (json \ "height").validate[Height]
        finalizedHeight         <- (json \ "finalizedHeight").validate[Height]
        currentGenerationPeriod <- readGenerationPeriod(activationHeight, json, "currentGenerationPeriod")
        nextGenerationPeriod    <- readGenerationPeriod(activationHeight, json, "nextGenerationPeriod")
      } yield FinalityStatus(height, finalizedHeight, currentGenerationPeriod, nextGenerationPeriod)

    }

  private def readGenerationPeriod(activationHeight: Option[Height], json: JsValue, fieldName: String) =
    activationHeight.fold(JsError())(h => (json \ fieldName).validateOpt[GenerationPeriod](using generationPeriodReads(h)))

  private def generationPeriodReads(activationHeight: Height): Reads[GenerationPeriod] =
    (
      (__ \ "start").read[Height] and (__ \ "end").read[Height]
    )((start, end) =>
      // Invert GenerationPeriod.end: end = start + length + (if isZero 0 else -1), isZero == (start == activation).
      // So length = end - start for the zero period, but end - start + 1 for any later period. Using end - start
      // unconditionally under-counts non-zero periods by one, which throws off .next/.end/.prev on the client.
      GenerationPeriod(activationHeight, start, if (start == activationHeight) end - start else end - start + 1)
    )
}
