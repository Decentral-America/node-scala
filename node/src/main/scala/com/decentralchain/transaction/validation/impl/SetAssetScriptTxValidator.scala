package com.decentralchain.transaction.validation.impl

import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.assets.SetAssetScriptTransaction
import com.decentralchain.transaction.validation.{TxValidator, ValidatedV}

object SetAssetScriptTxValidator extends TxValidator[SetAssetScriptTransaction] {
  override def validate(tx: SetAssetScriptTransaction): ValidatedV[SetAssetScriptTransaction] = {
    import tx.*
    V.seq(tx)(
      V.cond(
        script.forall(_.isInstanceOf[ExprScript]),
        GenericError(s"Asset can only be assigned with Expression script, not Contract")
      ),
      V.cond(
        script.isDefined,
        GenericError("Cannot set empty script")
      )
    )
  }
}
