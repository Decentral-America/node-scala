package com.decentralchain.ride.runner.requests

import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import com.decentralchain.api.http.utils.Evaluation

final case class RideScriptRunResult(
    evaluation: Option[Evaluation],
    lastResult: String,
    lastStatus: StatusCode
)

object RideScriptRunResult {
  def apply(): RideScriptRunResult = RideScriptRunResult(
    None,
    "",
    StatusCodes.InternalServerError
  )
}
