package com.decentralchain.api.http.requests

import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.{ProvenTransaction, Transaction}

trait TxBroadcastRequest[+T <: Transaction & ProvenTransaction] {
  def toTx: Either[ValidationError, T]
}
