package com.decentralchain.transaction

import com.decentralchain.account.*
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey, BlsSignature}
import com.decentralchain.lang.ValidationError
import com.decentralchain.state.Height
import com.decentralchain.transaction.serialization.impl.{BaseTxJson, PBTransactionSerializer}
import com.decentralchain.transaction.validation.TxValidator
import com.decentralchain.transaction.validation.impl.CommitToGenerationTxValidator
import monix.eval.Coeval
import play.api.libs.json.*

final case class CommitToGenerationTransaction(
    override val version: TxVersion,
    sender: PublicKey,
    endorserPublicKey: BlsPublicKey,
    generationPeriodStart: Height,
    timestamp: TxTimestamp,
    fee: TxPositiveAmount,
    commitmentSignature: BlsSignature,
    proofs: Proofs,
    override val chainId: Byte
) extends Transaction(TransactionType.CommitToGeneration)
    with ProvenTransaction
    with Versioned.ConstV1
    with TxWithFee.InDcc
    with FastHashId
    with PBSince.V1 {

  override type T = CommitToGenerationTransaction

  override def addProof(proof: ByteStr): CommitToGenerationTransaction = copy(proofs = proofs.add(proof))

  override val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(PBTransactionSerializer.bodyBytes(this))
  override val bytes: Coeval[Array[Byte]]     = Coeval.evalOnce(PBTransactionSerializer.bytes(this))
  override val json: Coeval[JsObject] =
    Coeval.evalOnce(
      BaseTxJson.toJson(this) ++ Json.obj(
        "endorserPublicKey"     -> endorserPublicKey.base58,
        "generationPeriodStart" -> generationPeriodStart,
        "commitmentSignature"   -> commitmentSignature.base58
      )
    )

  lazy val popMessage: Array[Byte] = CommitToGenerationTransaction.mkPopMessage(endorserPublicKey, generationPeriodStart)
}

object CommitToGenerationTransaction {
  val DepositInDcclets = 100_00000000L

  implicit val validator: TxValidator[CommitToGenerationTransaction] = CommitToGenerationTxValidator

  implicit def signed(tx: CommitToGenerationTransaction, privateKey: PrivateKey): CommitToGenerationTransaction =
    tx.copy(proofs = Proofs(crypto.sign(privateKey, tx.bodyBytes())))

  def mkPopSignature(blsKeyPair: BlsKeyPair, generationPeriodStart: Height): BlsSignature =
    blsKeyPair.sign(mkPopMessage(blsKeyPair.publicKey, generationPeriodStart))

  def mkPopMessage(blsPublicKey: BlsPublicKey, generationPeriodStart: Height): Array[Byte] =
    blsPublicKey.arr ++ generationPeriodStart.toByteArray

  def create(
      version: TxVersion,
      sender: PublicKey,
      endorserPublicKey: BlsPublicKey,
      generationPeriodStart: Height,
      timestamp: TxTimestamp,
      feeInDcc: Long,
      commitmentSignature: BlsSignature,
      proofs: Proofs,
      chainId: Byte
  ): Either[ValidationError, CommitToGenerationTransaction] =
    for {
      feeInDcc <- TxPositiveAmount(feeInDcc)(TxValidationError.InsufficientFee)
      tx <- CommitToGenerationTransaction(
        version,
        sender,
        endorserPublicKey,
        generationPeriodStart,
        timestamp,
        feeInDcc,
        commitmentSignature,
        proofs,
        chainId
      ).validatedEither
    } yield tx

  def selfSigned(
      version: TxVersion,
      sender: KeyPair,
      endorserPublicKey: BlsPublicKey,
      generationPeriodStart: Height,
      timestamp: TxTimestamp,
      feeInDcc: Long,
      commitmentSignature: BlsSignature,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, CommitToGenerationTransaction] =
    create(version, sender.publicKey, endorserPublicKey, generationPeriodStart, timestamp, feeInDcc, commitmentSignature, Proofs.empty, chainId)
      .map(signed(_, sender.privateKey))
}
