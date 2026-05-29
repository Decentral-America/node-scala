package com.decentralchain.transaction.validation.impl

import cats.data.ValidatedNel
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.lease.LeaseTransaction
import com.decentralchain.transaction.validation.TxValidator
import com.decentralchain.transaction.{TxPositiveAmount, TxValidationError}

object LeaseTxValidator extends TxValidator[LeaseTransaction] {
  override def validate(tx: LeaseTransaction): ValidatedNel[ValidationError, LeaseTransaction] = {
    import tx.*
    V.seq(tx)(
      V.noOverflow(amount.value, fee.value),
      V.cond(sender.toAddress != recipient, TxValidationError.ToSelf),
      V.addressChainId(recipient, chainId)
    )
  }

  def validateAmount(amount: Long): Either[ValidationError, TxPositiveAmount] =
    TxPositiveAmount.from(amount).left.map[ValidationError](_ => TxValidationError.NonPositiveAmount(amount, "dcc"))
}
