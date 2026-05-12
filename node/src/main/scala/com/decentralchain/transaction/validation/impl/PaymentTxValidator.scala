package com.decentralchain.transaction.validation.impl

import cats.data.ValidatedNel
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.PaymentTransaction
import com.decentralchain.transaction.validation.TxValidator

object PaymentTxValidator extends TxValidator[PaymentTransaction] {
  override def validate(transaction: PaymentTransaction): ValidatedNel[ValidationError, PaymentTransaction] = {
    import transaction.*
    V.seq(transaction)(
      V.noOverflow(fee.value, amount.value),
      V.addressChainId(recipient, chainId)
    )
  }
}
