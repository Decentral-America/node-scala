package com.decentralchain.test

import com.decentralchain.account.{AddressOrAlias, KeyPair}
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.lang.v1.compiler.Terms
import com.decentralchain.transaction.smart.InvokeScriptTransaction
import com.decentralchain.transaction.{Asset, Proofs, TxTimestamp, TransactionSignOps}

object Signed {
  def invokeScript(
      version: Byte,
      sender: KeyPair,
      dApp: AddressOrAlias,
      functionCall: Option[Terms.FUNCTION_CALL],
      payments: Seq[InvokeScriptTransaction.Payment],
      fee: Long,
      feeAssetId: Asset,
      timestamp: TxTimestamp
  ): InvokeScriptTransaction =
    InvokeScriptTransaction
      .create(version, sender.publicKey, dApp, functionCall, payments, fee, feeAssetId, timestamp, Proofs.empty, dApp.chainId)
      .map(_.signWith(sender.privateKey))
      .explicitGet()
}
