package com.decentralchain.ride.runner.entrypoints

import com.typesafe.config.ConfigFactory
import com.decentralchain.ride.runner.input.RideRunnerInputParser
import com.decentralchain.{BaseTestSuite, HasTestAccounts}
import play.api.libs.json.JsSuccess

class RideRunnerWithPreparedStateTestSuite extends BaseTestSuite with HasTestAccounts {
  "RideRunnerWithPreparedState" in {
    val sampleInput = ConfigFactory.parseResources("sample-input.conf")
    val input       = RideRunnerInputParser.from(RideRunnerInputParser.prepare(sampleInput))
    val r           = DCCRideRunnerWithPreparedStateApp.run(input)
    (r \ "result" \ "value" \ "_2" \ "value").validate[BigInt] shouldBe JsSuccess(BigInt("9007199361531057"))
  }
}
