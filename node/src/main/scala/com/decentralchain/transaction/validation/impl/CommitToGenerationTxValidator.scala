package com.decentralchain.transaction.validation.impl

import cats.data.Validated.Valid
import com.decentralchain.transaction.CommitToGenerationTransaction
import com.decentralchain.transaction.validation.*

object CommitToGenerationTxValidator extends TxValidator[CommitToGenerationTransaction] {
  override def validate(tx: CommitToGenerationTransaction): ValidatedV[CommitToGenerationTransaction] =
    Valid(tx) // Nothing to validate
}
