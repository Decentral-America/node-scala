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

  /** Canonical PoP message: chainId ‖ senderPublicKey ‖ endorserPublicKey ‖ generationPeriodStart (85 bytes).
    * chainId defeats cross-chain PoP replay (BLS audit M2); sender defeats mempool PoP lifting (M2).
    *
    * The single source of truth for the bytes a proof of possession covers. Previously constructed
    * by hand in THREE places (`mkPopSignature`, `CommitToGenerationTransactionDiff`,
    * `BlockDiffer.validateCommitmentsOnSnapshotPath`); a divergence between any two of them is a
    * consensus split, so there is now exactly one implementation.
    */
  def popMessage(chainId: Byte, sender: PublicKey, endorserPublicKey: BlsPublicKey, generationPeriodStart: Height): Array[Byte] =
    Array(chainId) ++ sender.arr ++ endorserPublicKey.arr ++ generationPeriodStart.toByteArray

  val PopDst: String = BlsUtils.BlsPopDomainSeparationTag

  /** LEGACY, VERIFY-ONLY PoP message layout: `endorserPublicKey(48) ‖ generationPeriodStart(4)`, no
    * chainId, no sender. This is the layout every `CommitToGenerationTransaction` already on the
    * testnet-relaunch chain (from height ~12 onward) was actually signed under, before commit
    * `448d56557f` introduced the chain/sender-bound v2 layout above. Never used to construct new
    * commitments -- only [[verifyPop]] falls back to it, to keep historical on-chain commitments
    * re-verifiable (e.g. on a full resync from genesis).
    */
  private def legacyPopMessage(endorserPublicKey: BlsPublicKey, generationPeriodStart: Height): Array[Byte] =
    endorserPublicKey.arr ++ generationPeriodStart.toByteArray

  def mkPopSignature(
      blsKeyPair: BlsKeyPair,
      generationPeriodStart: Height,
      sender: PublicKey,
      chainId: Byte
  ): BlsSignature =
    blsKeyPair.sign(popMessage(chainId, sender, blsKeyPair.publicKey, generationPeriodStart), PopDst)

  /** Verifies a `CommitToGenerationTransaction`'s BLS proof of possession. Tries the current v2
    * scheme (chain/sender-bound message + per-context `_POP_` DST) first; if that fails, falls back
    * to the legacy scheme (bare `endorserPk ‖ periodStart` message + legacy `_NUL_` DST) so
    * historical on-chain commitments signed before the v2 scheme existed still verify. Signing
    * (constructing NEW commitments) stays v2-only -- see [[mkPopSignature]]; this fallback exists
    * purely so already-on-chain, legacy-signed transactions remain verifiable forever, e.g. during a
    * full resync from genesis.
    */
  def verifyPop(
      signature: Array[Byte],
      chainId: Byte,
      sender: PublicKey,
      endorserPublicKey: BlsPublicKey,
      generationPeriodStart: Height
  ): Boolean =
    BlsUtils
      .verifyBasic(signature, popMessage(chainId, sender, endorserPublicKey, generationPeriodStart), endorserPublicKey.arr, PopDst)
      .isRight ||
      BlsUtils
        .verifyBasic(
          signature,
          legacyPopMessage(endorserPublicKey, generationPeriodStart),
          endorserPublicKey.arr,
          BlsUtils.BlsLegacyDomainSeparationTag
        )
        .isRight

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
