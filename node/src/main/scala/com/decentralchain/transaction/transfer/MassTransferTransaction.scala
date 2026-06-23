package com.decentralchain.transaction.transfer

import cats.instances.list.*
import cats.syntax.traverse.*
import com.decentralchain.account.*
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.*
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.TxValidationError.*
import com.decentralchain.transaction.serialization.impl.MassTransferTxSerializer
import com.decentralchain.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.decentralchain.transaction.validation.TxValidator
import com.decentralchain.transaction.validation.impl.MassTransferTxValidator
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json, OFormat}

import scala.util.{Either, Try}

case class MassTransferTransaction(
    version: TxVersion,
    sender: PublicKey,
    assetId: Asset,
    transfers: Seq[ParsedTransfer],
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    attachment: ByteStr,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(
      TransactionType.MassTransfer,
      assetId match {
        case Dcc          => Seq()
        case a: IssuedAsset => Seq(a)
      }
    ),
      ProvenTransaction,
      Versioned.ToV2,
      TxWithFee.InDcc,
      FastHashId,
      PBSince.V2 {
  override type T = MassTransferTransaction

  override val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(MassTransferTxSerializer.bodyBytes(this))
  override val bytes: Coeval[Array[Byte]]     = Coeval.evalOnce(MassTransferTxSerializer.toBytes(this))
  override val json: Coeval[JsObject]         = Coeval.evalOnce(MassTransferTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): MassTransferTransaction = copy(proofs = this.proofs.add(proof))

  def compactJson(recipient: Address, aliases: Set[Alias]): JsObject =
    json() ++ Json.obj(
      "transfers" -> MassTransferTxSerializer.transfersJson(transfers.filter { t =>
        t.address match {
          case a: Address => a == recipient
          case a: Alias   => aliases(a)
        }
      })
    )
}

object MassTransferTransaction extends TransactionParser {
  type TransactionT = MassTransferTransaction

  val MaxTransferCount = 100

  override val typeId: TxType = 11: Byte

  implicit val validator: TxValidator[MassTransferTransaction] = MassTransferTxValidator

  override def parseBytes(bytes: Array[Byte]): Try[MassTransferTransaction] =
    MassTransferTxSerializer.parseBytes(bytes)

  case class Transfer(
      recipient: String,
      amount: Long
  )

  object Transfer {
    implicit val jsonFormat: OFormat[Transfer] = Json.format[Transfer]
  }

  case class ParsedTransfer(address: AddressOrAlias, amount: TxNonNegativeAmount)

  def create(
      version: TxVersion,
      sender: PublicKey,
      assetId: Asset,
      transfers: Seq[ParsedTransfer],
      fee: Long,
      timestamp: TxTimestamp,
      attachment: ByteStr,
      proofs: Proofs,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, MassTransferTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- MassTransferTransaction(version, sender, assetId, transfers, fee, timestamp, attachment, proofs, chainId).validatedEither
    } yield tx

  def parseTransfersList(transfers: List[Transfer]): Validation[List[ParsedTransfer]] =
    transfers.traverse { case Transfer(recipient, amount) =>
      for {
        addressOrAlias <- AddressOrAlias.fromString(recipient)
        transferAmount <- TxNonNegativeAmount(amount)(NegativeAmount(amount, "asset"))
      } yield {
        ParsedTransfer(addressOrAlias, transferAmount)
      }
    }

}
