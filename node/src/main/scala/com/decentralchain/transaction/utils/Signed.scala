package com.decentralchain.transaction.utils

import com.decentralchain.account.{AddressOrAlias, KeyPair}
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.lang.script.Script
import com.decentralchain.lang.v1.compiler.Terms
import com.decentralchain.transaction.assets.IssueTransaction
import com.decentralchain.transaction.smart.{InvokeScriptTransaction, SetScriptTransaction}
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

  def issue(
      version: Byte,
      sender: KeyPair,
      name: String,
      description: String,
      quantity: Long,
      decimals: Byte,
      reissuable: Boolean,
      script: Option[Script],
      fee: Long,
      timestamp: TxTimestamp
  ): IssueTransaction =
    IssueTransaction
      .create(version, sender.publicKey, name, description, quantity, decimals, reissuable, script, fee, timestamp, Proofs.empty)
      .map(_.signWith(sender.privateKey))
      .explicitGet()

  def setScript(
      version: Byte,
      sender: KeyPair,
      script: Option[Script],
      fee: Long,
      timestamp: TxTimestamp
  ): SetScriptTransaction =
    SetScriptTransaction
      .create(version, sender.publicKey, script, fee, timestamp, Proofs.empty)
      .map(_.signWith(sender.privateKey))
      .explicitGet()
}
