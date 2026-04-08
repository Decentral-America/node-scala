package com.decentralchain.transaction

import com.decentralchain.account.PrivateKey
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto

trait ProvenTransaction extends Proven { this: Transaction =>
  type T <: Transaction
  def addProof(proof: ByteStr): T
}

object ProvenTransaction {
  extension (p: ProvenTransaction) {
    def signWith(privateKey: PrivateKey): p.T = p.addProof(crypto.sign(privateKey, p.bodyBytes()))
  }
}
