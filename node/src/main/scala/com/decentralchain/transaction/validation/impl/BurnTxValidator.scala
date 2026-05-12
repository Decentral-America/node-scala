package com.decentralchain.transaction.validation.impl

import cats.syntax.validated.*
import com.decentralchain.transaction.assets.BurnTransaction
import com.decentralchain.transaction.validation.{TxValidator, ValidatedV}

object BurnTxValidator extends TxValidator[BurnTransaction] {
  override def validate(tx: BurnTransaction): ValidatedV[BurnTransaction] = tx.validNel
}
