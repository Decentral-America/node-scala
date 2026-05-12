package com.decentralchain.transaction.validation.impl

import com.decentralchain.account.{Address, Alias}
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.transfer.MassTransferTransaction
import com.decentralchain.transaction.transfer.MassTransferTransaction.MaxTransferCount
import com.decentralchain.transaction.validation.{TxValidator, ValidatedV}

object MassTransferTxValidator extends TxValidator[MassTransferTransaction] {
  override def validate(tx: MassTransferTransaction): ValidatedV[MassTransferTransaction] = {
    import tx.*
    V.seq(tx)(
      V.noOverflow((fee.value +: transfers.map(_.amount.value))*),
      V.cond(transfers.length <= MaxTransferCount, GenericError(s"Number of transfers ${transfers.length} is greater than $MaxTransferCount")),
      V.transferAttachment(attachment),
      V.chainIds(
        chainId,
        transfers.view
          .map(_.address)
          .collect {
            case wa: Address => wa.chainId
            case wl: Alias   => wl.chainId
          }
          .toSeq*
      )
    )
  }
}
