package com.decentralchain.transaction.validation.impl

import cats.syntax.either.*
import com.decentralchain.lang.v1.ContractLimits
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.smart.InvokeExpressionTransaction
import com.decentralchain.transaction.validation.{TxValidator, ValidatedV}

object InvokeExpressionTxValidator extends TxValidator[InvokeExpressionTransaction] {
  override def validate(tx: InvokeExpressionTransaction): ValidatedV[InvokeExpressionTransaction] = {
    val size  = tx.expressionBytes.size
    val limit = ContractLimits.MaxContractSizeInBytes
    V.seq(tx)(
      Either
        .cond(
          size <= limit,
          (),
          GenericError(s"InvokeExpressionTransaction bytes length = $size exceeds limit = $limit")
        )
        .toValidatedNel
    )
  }
}
