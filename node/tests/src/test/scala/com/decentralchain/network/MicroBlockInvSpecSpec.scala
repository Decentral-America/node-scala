package com.decentralchain.network

import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.*
import com.decentralchain.test.FreeSpec
import org.scalacheck.Gen

class MicroBlockInvSpecSpec extends FreeSpec {

  private val microBlockInvGen: Gen[MicroBlockInv] = for {
    acc          <- accountGen
    totalSig     <- byteArrayGen(SignatureLength)
    prevBlockSig <- byteArrayGen(SignatureLength)
  } yield MicroBlockInv(acc, ByteStr(totalSig), ByteStr(prevBlockSig))

  "MicroBlockInvMessageSpec" - {
    import MicroBlockInvSpec.*

    "deserializeData(serializedData(data)) == data" in forAll(microBlockInvGen) { inv =>
      inv.signaturesValid() should beRight
      val restoredInv = deserializeData(serializeData(inv)).get
      restoredInv.signaturesValid() should beRight

      restoredInv shouldBe inv
    }
  }

}
