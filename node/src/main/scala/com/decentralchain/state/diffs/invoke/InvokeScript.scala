package com.decentralchain.state.diffs.invoke

import com.decentralchain.account.*
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.v1.compiler.Terms.*
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.smart.InvokeScriptTransaction
import com.decentralchain.transaction.smart.InvokeScriptTransaction.Payment
import com.decentralchain.transaction.{Authorized, TransactionBase, TxTimestamp}

trait InvokeScriptLike {
  def dApp: AddressOrAlias
  def funcCall: FUNCTION_CALL
  def payments: Seq[Payment]
  def root: InvokeScriptTransactionLike
  def sender: PublicKey
}

trait InvokeScriptTransactionLike extends TransactionBase with InvokeScriptLike with Authorized

object InvokeScriptLike {
  implicit class ISLExt(val isl: InvokeScriptLike) extends AnyVal {
    def enableEmptyKeys: Boolean = isl.root match {
      case ist: InvokeScriptTransaction => ist.version == 1
      case _                            => true
    }

    def paymentAssets: Seq[IssuedAsset] = isl.payments.collect(IssuedAssets)

    def txId: ByteStr          = isl.root.id()
    def timestamp: TxTimestamp = isl.root.timestamp
  }

  val IssuedAssets: PartialFunction[Payment, IssuedAsset] = { case Payment(_, assetId: IssuedAsset) => assetId }
}

case class InvokeScript(
    sender: PublicKey,
    dApp: Address,
    funcCall: FUNCTION_CALL,
    payments: Seq[Payment],
    root: InvokeScriptTransactionLike
) extends InvokeScriptLike
