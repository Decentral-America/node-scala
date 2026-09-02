package com.decentralchain.transaction

import com.decentralchain.account.*
import com.decentralchain.crypto
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey, BlsSignature, BlsUtils}
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
  override val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(PBTransactionSerializer.bodyBytes(this))
  override val bytes: Coeval[Array[Byte]]     = Coeval.evalOnce(PBTransactionSerializer.bytes(this))
  override val json: Coeval[JsObject]         =
    Coeval.evalOnce(
      BaseTxJson.toJson(this) ++ Json.obj(
        "endorserPublicKey"     -> endorserPublicKey.base58,
        "generationPeriodStart" -> generationPeriodStart,
        "commitmentSignature"   -> commitmentSignature.base58
      )
    )
}

object CommitToGenerationTransaction {
  val DepositInDcclets = 100_00000000L

  implicit val validator: TxValidator[CommitToGenerationTransaction] = CommitToGenerationTxValidator

  implicit def signed(tx: CommitToGenerationTransaction, privateKey: PrivateKey): CommitToGenerationTransaction =
    tx.copy(proofs = Proofs(crypto.sign(privateKey, tx.bodyBytes())))

  /** The single source of truth for the bytes a proof of possession covers. Previously constructed
    * by hand in THREE places (`mkPopSignature`, `CommitToGenerationTransactionDiff`,
    * `BlockDiffer.validateCommitmentsOnSnapshotPath`); a divergence between any two of them is a
    * consensus split, so there is now exactly one implementation.
    *
    * `cryptoV2 = false` reproduces the legacy layout `endorserPk(48) ‖ periodStart(4)` byte-for-byte
    * -- it MUST keep doing so forever, because that is what every PoP already on chain signed.
    *
    * `cryptoV2 = true` is the audit-M2 layout `chainId(1) ‖ senderPk(32) ‖ endorserPk(48) ‖
    * periodStart(4)`, which binds the PoP to BOTH the network and the registering account: a PoP
    * harvested from testnet is no longer a valid mainnet PoP, and a PoP lifted out of the mempool
    * cannot be resubmitted under a different sender to front-run the original registration.
    * Verification pairs this with the `_POP_` DST (H2), so a v2 PoP is also unusable in the
    * endorsement or HotStuff-vote contexts.
    */
  def popMessage(
      chainId: Byte,
      sender: PublicKey,
      endorserPublicKey: BlsPublicKey,
      generationPeriodStart: Height,
      cryptoV2: Boolean
  ): Array[Byte] =
    if (cryptoV2) Array(chainId) ++ sender.arr ++ endorserPublicKey.arr ++ generationPeriodStart.toByteArray
    else endorserPublicKey.arr ++ generationPeriodStart.toByteArray

  /** The DST a PoP is produced/verified under, for the given era. */
  def popDst(cryptoV2: Boolean): String =
    if (cryptoV2) BlsUtils.BlsPopDomainSeparationTagV2 else BlsUtils.BlsDomainSeparationTag

  def mkPopSignature(
      blsKeyPair: BlsKeyPair,
      generationPeriodStart: Height,
      sender: PublicKey,
      chainId: Byte,
      cryptoV2: Boolean
  ): BlsSignature =
    blsKeyPair.sign(popMessage(chainId, sender, blsKeyPair.publicKey, generationPeriodStart, cryptoV2), popDst(cryptoV2))

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
      tx       <- CommitToGenerationTransaction(
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
