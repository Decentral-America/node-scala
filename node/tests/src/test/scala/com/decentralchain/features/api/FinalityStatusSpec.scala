package com.decentralchain.features.api

import com.decentralchain.state.{GenerationPeriod, Height}
import com.decentralchain.test.*
import org.scalatest.OptionValues
import play.api.libs.json.*

class FinalityStatusSpec extends FreeSpec with OptionValues {

  // Mirrors FinalityApiRoute's on-the-wire shape: a generation period is serialized as {start, end}.
  private def finalityJson(start: Int, end: Int): JsValue =
    Json.obj(
      "height"                  -> 25,
      "finalizedHeight"         -> 22,
      "currentGenerationPeriod" -> Json.obj("start" -> start, "end" -> end),
      "nextGenerationPeriod"    -> JsNull
    )

  "FinalityStatus.parse reconstructs a generation period so its length/.end/.next round-trip" - {
    "zero period [0, 20] (start == activation)" in {
      val activation = Height(0)
      val zero       = GenerationPeriod(activation, Height(0), 20) // end == 20
      val parsed     = finalityJson(zero.start.toInt, zero.end.toInt).as[FinalityStatus](FinalityStatus.parse(Some(activation)))
      parsed.currentGenerationPeriod.value shouldBe zero
      parsed.currentGenerationPeriod.value.next.start shouldBe Height(21)
    }

    "non-zero period [21, 40] — regression: length must be end - start + 1, not end - start" in {
      val activation = Height(0)
      val period1    = GenerationPeriod(activation, Height(21), 20) // end == 40
      val parsed     = finalityJson(period1.start.toInt, period1.end.toInt).as[FinalityStatus](FinalityStatus.parse(Some(activation)))
      parsed.currentGenerationPeriod.value shouldBe period1
      // The next period must start at 41; the old (end - start) length gave 39/40 here.
      parsed.currentGenerationPeriod.value.next.start shouldBe Height(41)
    }
  }
}
