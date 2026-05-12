package com.decentralchain.transaction.validation.impl

import cats.data.Validated
import com.decentralchain.account.Alias
import com.decentralchain.transaction.CreateAliasTransaction
import com.decentralchain.transaction.validation.{TxValidator, ValidatedV}

object CreateAliasTxValidator extends TxValidator[CreateAliasTransaction] {
  override def validate(tx: CreateAliasTransaction): ValidatedV[CreateAliasTransaction] = {
    import tx.*
    V.seq(tx)(
      Validated.fromEither(Alias.createWithChainId(aliasName, chainId, Some(chainId))).toValidatedNel.map((_: Alias) => tx)
    )
  }
}
