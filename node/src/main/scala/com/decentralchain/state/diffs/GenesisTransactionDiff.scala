package com.decentralchain.state.diffs

import com.decentralchain.lang.ValidationError
import com.decentralchain.state.{Blockchain, Portfolio, StateSnapshot}
import com.decentralchain.transaction.GenesisTransaction
import com.decentralchain.transaction.TxValidationError.GenericError

object GenesisTransactionDiff {
  def apply(b: Blockchain)(tx: GenesisTransaction): Either[ValidationError, StateSnapshot] = {
    if (b.height != 1)
      Left(GenericError(s"GenesisTransaction cannot appear in non-initial block (${b.height})"))
    else
      StateSnapshot.build(b, Map(tx.recipient -> Portfolio(balance = tx.amount.value)))
  }
}
