package com.decentralchain.transaction.validation.impl

import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.assets.IssueTransaction
import com.decentralchain.transaction.validation.{TxValidator, ValidatedV}
import com.decentralchain.transaction.TxVersion

object IssueTxValidator extends TxValidator[IssueTransaction] {
  override def validate(tx: IssueTransaction): ValidatedV[IssueTransaction] = {

    import tx.*
    V.seq(tx)(
      V.assetName(tx.name),
      V.assetDescription(tx.description),
      V.cond(version > TxVersion.V1 || script.isEmpty, GenericError("Script not supported")),
      V.cond(script.forall(_.isInstanceOf[ExprScript]), GenericError(s"Asset can only be assigned with Expression script, not Contract"))
    )
  }
}
