package com.decentralchain.api.common

import com.decentralchain.account.Address
import com.decentralchain.api.{BlockMeta, common}
import com.decentralchain.block
import com.decentralchain.block.Block
import com.decentralchain.block.Block.TransactionProof
import com.decentralchain.common.state.ByteStr
import com.decentralchain.database.RDB
import com.decentralchain.lang.ValidationError
import com.decentralchain.mining.BlockChallenger
import com.decentralchain.state.diffs.FeeValidation
import com.decentralchain.state.diffs.FeeValidation.FeeDetails
import com.decentralchain.state.{Blockchain, Height, StateSnapshot, TxMeta}
import com.decentralchain.transaction.TransactionType
import com.decentralchain.transaction.smart.script.trace.TracedResult
import com.decentralchain.transaction.{Asset, CreateAliasTransaction, Transaction}
import com.decentralchain.utx.UtxPool
import monix.reactive.Observable

import scala.concurrent.Future

trait CommonTransactionsApi {

  def aliasesOfAddress(address: Address): Observable[(Height, CreateAliasTransaction)]

  def transactionById(txId: ByteStr): Option[TransactionMeta]

  def unconfirmedTransactions: Seq[Transaction]

  def unconfirmedTransactionById(txId: ByteStr): Option[Transaction]

  def calculateFee(tx: Transaction): Either[ValidationError, (Asset, Long, Long)]

  def broadcastTransaction(tx: Transaction): Future[TracedResult[ValidationError, Boolean]]

  def transactionsByAddress(
      subject: Address,
      sender: Option[Address],
      transactionTypes: Set[TransactionType],
      fromId: Option[ByteStr] = None
  ): Observable[TransactionMeta]

  def transactionProofs(transactionIds: List[ByteStr]): List[TransactionProof]
}

object CommonTransactionsApi {
  def apply(
      maybeDiff: => Option[(Height, StateSnapshot)],
      rdb: RDB,
      blockchain: Blockchain,
      utx: UtxPool,
      blockChallenger: Option[BlockChallenger],
      publishTransaction: Transaction => Future[TracedResult[ValidationError, Boolean]],
      blockAt: Height => Option[(BlockMeta, Seq[(TxMeta, Transaction)])]
  ): CommonTransactionsApi = new CommonTransactionsApi {
    override def aliasesOfAddress(address: Address): Observable[(Height, CreateAliasTransaction)] =
      common.aliasesOfAddress(rdb, maybeDiff, address)

    override def transactionsByAddress(
        subject: Address,
        sender: Option[Address],
        transactionTypes: Set[TransactionType],
        fromId: Option[ByteStr] = None
    ): Observable[TransactionMeta] =
      common.addressTransactions(rdb, maybeDiff, subject, sender, transactionTypes, fromId)

    override def transactionById(transactionId: ByteStr): Option[TransactionMeta] =
      blockchain.transactionInfo(transactionId).map(common.loadTransactionMeta(rdb, maybeDiff))

    override def unconfirmedTransactions: Seq[Transaction] =
      utx.all ++ blockChallenger.fold(Seq.empty[Transaction])(_.allProcessingTxs)

    override def unconfirmedTransactionById(transactionId: ByteStr): Option[Transaction] =
      utx.transactionById(transactionId).orElse(blockChallenger.flatMap(_.getProcessingTx(transactionId)))

    override def calculateFee(tx: Transaction): Either[ValidationError, (Asset, Long, Long)] =
      FeeValidation
        .getMinFee(blockchain, tx)
        .map { case FeeDetails(asset, _, feeInAsset, feeInDcc) =>
          (asset, feeInAsset, feeInDcc)
        }

    override def broadcastTransaction(tx: Transaction): Future[TracedResult[ValidationError, Boolean]] = publishTransaction(tx)

    override def transactionProofs(transactionIds: List[ByteStr]): List[TransactionProof] =
      for {
        transactionId           <- transactionIds
        (txm, tx)               <- blockchain.transactionInfo(transactionId)
        (meta, allTransactions) <- blockAt(txm.height) if meta.header.version >= Block.ProtoBlockVersion
        transactionProof        <- block.transactionProof(tx, allTransactions.map(_._2))
      } yield transactionProof
  }
}
