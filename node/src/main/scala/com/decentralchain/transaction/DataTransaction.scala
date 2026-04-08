package com.decentralchain.transaction

import com.decentralchain.account.{AddressScheme, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.protobuf.transaction.PBTransactions
import com.decentralchain.state.*
import com.decentralchain.transaction.serialization.impl.DataTxSerializer
import com.decentralchain.transaction.validation.TxValidator
import com.decentralchain.transaction.validation.impl.DataTxValidator
import monix.eval.Coeval
import play.api.libs.json.*

import scala.util.Try

case class DataTransaction(
    version: TxVersion,
    sender: PublicKey,
    data: Seq[DataEntry[?]],
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.Data)
    with ProvenTransaction
    with Versioned.ToV2
    with TxWithFee.InDcc
    with FastHashId
    with PBSince.V2 {
  override type T = DataTransaction
  override def addProof(proof: ByteStr): DataTransaction = copy(proofs = this.proofs.add(proof))

  override val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(DataTxSerializer.bodyBytes(this))
  override val bytes: Coeval[Array[Byte]]     = Coeval.evalOnce(DataTxSerializer.toBytes(this))
  override val json: Coeval[JsObject]         = Coeval.evalOnce(DataTxSerializer.toJson(this))

  private[decentralchain] lazy val protoDataPayload = PBTransactions.protobuf(this).getDccTransaction.getDataTransaction.toByteArray
}

object DataTransaction extends TransactionParser {
  type TransactionT = DataTransaction

  val MaxBytes: Int       = 150 * 1024 // uses for RIDE CONST_STRING and CONST_BYTESTR
  val MaxProtoBytes: Int  = 165890     // uses for RIDE CONST_BYTESTR
  val MaxRideV6Bytes: Int = 165835     // (DataEntry.MaxPBKeySize + DataEntry.MaxValueSize) * 5
  val MaxEntryCount: Int  = 100

  override val typeId: TxType = 12: Byte

  implicit val validator: TxValidator[DataTransaction] = DataTxValidator

  override def parseBytes(bytes: Array[TxVersion]): Try[DataTransaction] =
    DataTxSerializer.parseBytes(bytes)

  def create(
      version: TxVersion,
      sender: PublicKey,
      data: Seq[DataEntry[?]],
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs = Proofs.empty,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, DataTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- DataTransaction(version, sender, data, fee, timestamp, proofs, chainId).validatedEither
    } yield tx
}
