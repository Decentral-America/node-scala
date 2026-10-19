package com.decentralchain.utils

import com.decentralchain.test.FlatSpec

import java.nio.charset.StandardCharsets.UTF_8

class UtilAppSmokeSpec extends FlatSpec {
  "UtilApp smoke" should "exercise P-256, BLS aggregation and the groth16 native library" in {
    UtilApp.Actions.doSmokeTest().map(new String(_, UTF_8)) shouldBe Right("smoke: p256=ok bls=ok groth16=ok")
  }
}
