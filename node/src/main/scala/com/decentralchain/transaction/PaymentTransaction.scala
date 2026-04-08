package com.decentralchain.transaction

import com.decentralchain.account.{Address, KeyPair, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.serialization.impl.PaymentTxSerializer
import com.decentralchain.transaction.validation.TxValidator
import com.decentralchain.transaction.validation.impl.PaymentTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

import scala.util.Try

case class PaymentTransaction(
    sender: PublicKey,
    recipient: Address,
    amount: TxPositiveAmount,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    signature: ByteStr,
    chainId: Byte
) extends Transaction(TransactionType.Payment)
    with ProvenTransaction
    with TxWithFee.InWaves {
  override type T = PaymentTransaction
  override def addProof(proof: ByteStr): PaymentTransaction = copy(signature = proof)

  val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(PaymentTxSerializer.bodyBytes(this))

  def proofs: Proofs = Proofs(signature)

  override val id: Coeval[ByteStr] = Coeval.evalOnce(signature)

  override val bytes: Coeval[Array[Byte]] = Coeval.evalOnce(PaymentTxSerializer.toBytes(this))
  override val json: Coeval[JsObject]     = Coeval.evalOnce(PaymentTxSerializer.toJson(this))
}

object PaymentTransaction extends TransactionParser {
  type TransactionT = PaymentTransaction

  override val typeId: TxType = 2: Byte

  override def parseBytes(bytes: Array[TxVersion]): Try[PaymentTransaction] =
    PaymentTxSerializer.parseBytes(bytes)

  implicit val validator: TxValidator[PaymentTransaction] = PaymentTxValidator

  def create(sender: KeyPair, recipient: Address, amount: Long, fee: Long, timestamp: Long): Either[ValidationError, PaymentTransaction] =
    create(sender.publicKey, recipient, amount, fee, timestamp, ByteStr.empty).map(unsigned => {
      unsigned.copy(signature = crypto.sign(sender.privateKey, unsigned.bodyBytes()))
    })

  def create(
      sender: PublicKey,
      recipient: Address,
      amount: Long,
      fee: Long,
      timestamp: Long,
      signature: ByteStr
  ): Either[ValidationError, PaymentTransaction] =
    for {
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      amount <- TxPositiveAmount(amount)(TxValidationError.NonPositiveAmount(amount, "dcc"))
      tx     <- PaymentTransaction(sender, recipient, amount, fee, timestamp, signature, recipient.chainId).validatedEither
    } yield tx
}
