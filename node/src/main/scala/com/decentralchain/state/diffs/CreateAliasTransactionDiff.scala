package com.decentralchain.state.diffs

import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.lang.ValidationError
import com.decentralchain.state.{Blockchain, Portfolio, StateSnapshot}
import com.decentralchain.transaction.CreateAliasTransaction
import com.decentralchain.transaction.TxValidationError.GenericError

object CreateAliasTransactionDiff {
  def apply(blockchain: Blockchain)(tx: CreateAliasTransaction): Either[ValidationError, StateSnapshot] =
    if (blockchain.isFeatureActivated(BlockchainFeatures.DataTransaction, blockchain.height) && !blockchain.canCreateAlias(tx.alias))
      Left(GenericError("Alias already claimed"))
    else if (tx.proofs.size > 1 && !blockchain.isFeatureActivated(BlockchainFeatures.RideV6))
      Left(GenericError("Invalid proofs size"))
    else
      StateSnapshot.build(
        blockchain,
        portfolios = Map(tx.sender.toAddress -> Portfolio(-tx.fee.value)),
        aliases = Map(tx.alias -> tx.sender.toAddress)
      )
}
