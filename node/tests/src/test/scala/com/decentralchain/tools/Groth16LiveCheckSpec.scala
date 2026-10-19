package com.decentralchain.tools

import com.decentralchain.common.utils.Base64
import com.decentralchain.test.FreeSpec

class Groth16LiveCheckSpec extends FreeSpec {
  "the live-check vector is a valid BLS12-381 Groth16 proof (node's own binding)" in {
    com.decentralchain.lang.Global.groth16Verify(
      Base64.decode(Groth16LiveCheck.Vk),
      Base64.decode(Groth16LiveCheck.Proof),
      Base64.decode(Groth16LiveCheck.Inputs)
    ) shouldBe true
  }

  "the dApp calls groth16Verify and stores the result under \"groth16\"" in {
    Groth16LiveCheck.DApp should include("groth16Verify(vk, proof, inputs)")
    Groth16LiveCheck.DApp should include("BooleanEntry(\"groth16\"")
  }
}
