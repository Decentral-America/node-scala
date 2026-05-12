package com.decentralchain

import com.decentralchain.serialization.Deser
import com.decentralchain.test.FreeSpec

class DeserializationTests extends FreeSpec {

  "serializeArray" - {
    "works with arrays < 32k" in {
      val byteArray = Array.fill(Short.MaxValue)(0.toByte)
      Deser.serializeArrayWithLength(byteArray) should not be empty
    }
    "IllegalArgumentException thrown with arrays > 32k" in {
      val byteArray = Array.fill(Short.MaxValue + 1)(0.toByte)
      an[IllegalArgumentException] should be thrownBy Deser.serializeArrayWithLength(byteArray)
    }
  }
}
