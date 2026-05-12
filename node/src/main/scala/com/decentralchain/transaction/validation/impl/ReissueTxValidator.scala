package com.decentralchain.transaction.validation.impl

import com.decentralchain.transaction.assets.ReissueTransaction
import com.decentralchain.transaction.validation.{TxValidator, ValidatedV}

object ReissueTxValidator extends TxValidator[ReissueTransaction] {
  override def validate(tx: ReissueTransaction): ValidatedV[ReissueTransaction] = V.seq(tx)()
}
