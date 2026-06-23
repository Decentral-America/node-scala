package com.decentralchain.transaction.lease

import com.decentralchain.account.{AddressOrAlias, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.*
import com.decentralchain.transaction.serialization.impl.LeaseTxSerializer
import com.decentralchain.transaction.validation.TxValidator
import com.decentralchain.transaction.validation.impl.LeaseTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

import scala.util.Try

final case class LeaseTransaction(
    version: TxVersion,
    sender: PublicKey,
    recipient: AddressOrAlias,
    amount: TxPositiveAmount,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.Lease),
      ProvenTransaction,
      HasSignature,
      Versioned.ToV3,
      TxWithFee.InDcc,
      FastHashId,
      PBSince.V3 {
  type T = LeaseTransaction

  override val bodyBytes: Coeval[Array[TxVersion]] = Coeval.evalOnce(LeaseTxSerializer.bodyBytes(this))
  override val bytes: Coeval[Array[TxVersion]]     = Coeval.evalOnce(LeaseTxSerializer.toBytes(this))
  override val json: Coeval[JsObject]              = Coeval.evalOnce(LeaseTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): LeaseTransaction = copy(proofs = this.proofs.add(proof))
}

object LeaseTransaction extends TransactionParser {
  type TransactionT = LeaseTransaction

  val typeId: TxType = 8: Byte

  implicit val validator: TxValidator[LeaseTransaction] = LeaseTxValidator

  override def parseBytes(bytes: Array[TxVersion]): Try[LeaseTransaction] =
    LeaseTxSerializer.parseBytes(bytes)

  def create(
      version: TxVersion,
      sender: PublicKey,
      recipient: AddressOrAlias,
      amount: Long,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs
  ): Either[ValidationError, TransactionT] = {
    for {
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      amount <- TxPositiveAmount(amount)(TxValidationError.NonPositiveAmount(amount, "dcc"))
      tx     <- LeaseTransaction(version, sender, recipient, amount, fee, timestamp, proofs, recipient.chainId).validatedEither
    } yield tx

  }
}
