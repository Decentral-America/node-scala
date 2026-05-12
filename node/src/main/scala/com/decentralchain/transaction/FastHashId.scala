package com.decentralchain.transaction

import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto
import monix.eval.Coeval

trait FastHashId extends Proven {
  val id: Coeval[ByteStr] = Coeval.evalOnce(ByteStr(crypto.fastHash(bodyBytes())))
}
