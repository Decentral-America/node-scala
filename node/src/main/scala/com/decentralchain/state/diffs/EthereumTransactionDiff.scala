package com.decentralchain.state.diffs

import cats.implicits.catsSyntaxSemigroup
import com.google.protobuf.ByteString
import com.decentralchain.crypto.EthereumKeyLength
import com.decentralchain.database.protobuf.EthereumTransactionMeta
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.lang.ValidationError
import com.decentralchain.lang.v1.serialization.SerdeV1
import io.decentralchain.protobuf.transaction.{PBAmounts, PBRecipients}
import com.decentralchain.state.diffs.invoke.{InvokeDiffsCommon, InvokeScriptTransactionDiff}
import com.decentralchain.state.{Blockchain, StateSnapshot}
import com.decentralchain.transaction.EthereumTransaction
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.smart.script.trace.TracedResult

object EthereumTransactionDiff {
  def meta(blockchain: Blockchain)(tx: EthereumTransaction): StateSnapshot = {
    val resultEi = tx.payload match {
      case et: EthereumTransaction.Transfer =>
        for {
          _       <- et.checkTransferDataSize(blockchain, tx.underlying.getData)
          assetId <- et.tryResolveAsset(blockchain)
        } yield StateSnapshot(
          ethereumTransactionMeta = Map(
            tx.id() -> EthereumTransactionMeta(
              EthereumTransactionMeta.Payload.Transfer(
                EthereumTransactionMeta.Transfer(
                  ByteString.copyFrom(PBRecipients.publicKeyHash(et.recipient)),
                  Some(PBAmounts.fromAssetAndAmount(assetId, et.amount))
                )
              )
            )
          )
        )

      case ei: EthereumTransaction.Invocation =>
        for {
          invocation <- ei.toInvokeScriptLike(tx, blockchain)
        } yield StateSnapshot(
          ethereumTransactionMeta = Map(
            tx.id() -> EthereumTransactionMeta(
              EthereumTransactionMeta.Payload.Invocation(
                EthereumTransactionMeta.Invocation(
                  ByteString.copyFrom(SerdeV1.serialize(invocation.funcCall)),
                  invocation.payments.map(p => PBAmounts.fromAssetAndAmount(p.assetId, p.amount))
                )
              )
            )
          )
        )
    }
    resultEi.getOrElse(StateSnapshot.empty)
  }

  def apply(blockchain: Blockchain, currentBlockTs: Long, limitedExecution: Boolean, enableExecutionLog: Boolean)(
      tx: EthereumTransaction
  ): TracedResult[ValidationError, StateSnapshot] = {
    val baseDiff = tx.payload match {
      case et: EthereumTransaction.Transfer =>
        for {
          _             <- checkLeadingZeros(tx, blockchain)
          _             <- TracedResult(et.checkTransferDataSize(blockchain, tx.underlying.getData))
          asset         <- TracedResult(et.tryResolveAsset(blockchain))
          transfer      <- TracedResult(et.toTransferLike(tx, blockchain))
          assetSnapshot <- TransactionDiffer.assetsVerifierDiff(
            blockchain,
            transfer,
            verify = true,
            StateSnapshot(),
            Int.MaxValue,
            enableExecutionLog
          )
          snapshot <- TransferDiff(blockchain)(tx.senderAddress(), et.recipient, et.amount, asset, tx.fee, tx.feeAssetId)
        } yield assetSnapshot |+| snapshot

      case ei: EthereumTransaction.Invocation =>
        for {
          _              <- checkLeadingZeros(tx, blockchain)
          invocation     <- TracedResult(ei.toInvokeScriptLike(tx, blockchain))
          _              <- TracedResult(InvokeDiffsCommon.checkPayments(blockchain, invocation.payments))
          snapshot       <- InvokeScriptTransactionDiff(blockchain, currentBlockTs, limitedExecution, enableExecutionLog)(invocation)
          resultSnapshot <- TransactionDiffer.assetsVerifierDiff(
            blockchain,
            invocation,
            verify = true,
            snapshot,
            Int.MaxValue,
            enableExecutionLog
          )
        } yield snapshot.copy(scriptsComplexity = resultSnapshot.scriptsComplexity)
    }

    baseDiff.map(_ |+| meta(blockchain)(tx))
  }

  private def checkLeadingZeros(tx: EthereumTransaction, blockchain: Blockchain): TracedResult[ValidationError, Unit] = {
    TracedResult(
      Either.cond(
        !(tx.signerKeyBigInt().toByteArray.length < EthereumKeyLength) || blockchain.isFeatureActivated(BlockchainFeatures.ConsensusImprovements),
        (),
        GenericError("Invalid public key")
      )
    )
  }
}
