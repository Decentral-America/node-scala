package com.decentralchain.api.http.requests

import com.decentralchain.account.*
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey, BlsSignature}
import com.decentralchain.lang.ValidationError
import com.decentralchain.state.Height
import com.decentralchain.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import com.decentralchain.transaction.{CommitToGenerationTransaction, Proofs, TransactionType, TxVersion}
import play.api.libs.json.*

object CommitToGenerationRequest {
  given OFormat[CommitToGenerationRequest]       = Json.format
  given OFormat[SignedCommitToGenerationRequest] = Json.format
}

/** @param sender Address
  */
case class CommitToGenerationRequest(
    version: Option[TxVersion] = None,
    sender: Option[String],
    endorserPublicKey: Option[ByteStr] = None,
    generationPeriodStart: Option[Height] = None,
    timestamp: Option[Long] = None,
    fee: Option[Long] = None,
    commitmentSignature: Option[ByteStr] = None,
    chainId: Option[Byte] = None
) {
  /** @param cryptoV2 Which PoP era to sign the auto-generated commitment signature under (audit M2).
    *   The caller must derive this from the SAME height the transaction will actually be validated
    *   at -- i.e. `blockchain.supportsBlsCryptoV2` evaluated at (an estimate of) the containing
    *   block's height, not a hardcoded constant. A mismatch here doesn't corrupt consensus (the
    *   on-chain gate in `CommitToGenerationTransactionDiff` is still the source of truth and would
    *   just reject the tx), but it does mean the node would sign a PoP that fails validation the
    *   moment it lands in a block from the "other" side of the activation height. Ignored when the
    *   caller supplies an explicit `commitmentSignature` (nothing here to (re)compute).
    */
  def toTxFrom(
      senderPk: PublicKey,
      defaultEndorserKp: => BlsKeyPair,
      defaultGenerationPeriodStart: Height,
      defaultTimestamp: => Long,
      cryptoV2: Boolean
  ): Either[ValidationError, CommitToGenerationTransaction] = {
    val exactGenerationPeriodStart = generationPeriodStart.getOrElse(defaultGenerationPeriodStart)
    val exactChainId               = chainId.getOrElse(AddressScheme.current.chainId)
    for {
      commitmentSignature <- commitmentSignature match {
        case Some(r) => BlsSignature(r)
        case None    =>
          Right(
            CommitToGenerationTransaction
              .mkPopSignature(defaultEndorserKp, exactGenerationPeriodStart, senderPk, exactChainId, cryptoV2)
          )
      }
      endorserPublicKey <- endorserPublicKey match {
        case Some(endorserPublicKey) => BlsPublicKey(endorserPublicKey)
        case None                    => Right(defaultEndorserKp.publicKey)
      }
      tx <- CommitToGenerationTransaction.create(
        version.getOrElse(1.toByte),
        senderPk, // sender is address, we need a public key
        endorserPublicKey,
        exactGenerationPeriodStart,
        timestamp.getOrElse(defaultTimestamp),
        fee.getOrElse(FeeConstants(TransactionType.CommitToGeneration) * FeeUnit),
        commitmentSignature,
        Proofs.empty,
        chainId.getOrElse(AddressScheme.current.chainId)
      )
    } yield tx
  }
}

/** Upstream PR #4037 collapsed the sign-path and broadcast-path request shapes for this transaction
  * into one `TxBroadcastRequest[CommitToGenerationTransaction]`, removing the equivalent of this class
  * and the node-side auto-signing path (`mkPopSignature` from a local `BlsKeyPair`, see
  * `CommitToGenerationRequest.toTxFrom` above).
  *
  * DCC deliberately keeps both: confirmed via `git grep -rn "SignedCommitToGenerationRequest\|mkPopSignature"`
  * that `TransactionFactory.scala` has two live call sites -- one for `/transactions/sign` (uses the
  * auto-sign `CommitToGenerationRequest` above, letting a node fill in its own BLS PoP signature from
  * a wallet-held key rather than requiring the caller to pre-sign) and one for `/transactions/broadcast`
  * (uses this fully-pre-signed `SignedCommitToGenerationRequest`). Removing the auto-sign path would
  * break the sign endpoint; this class already provides the equivalent of upstream's consolidated
  * shape's *required* fields (senderPublicKey/endorserPublicKey/generationPeriodStart/
  * commitmentSignature, no auto-fill) for the broadcast path. This is an intentional, permanent DCC
  * divergence, not a remaining upstream-sync gap -- see Task 6 of the upstream-sync-port plan.
  */
case class SignedCommitToGenerationRequest(
    version: Option[TxVersion],
    senderPublicKey: String,
    endorserPublicKey: ByteStr,
    generationPeriodStart: Int,
    timestamp: Long,
    fee: Long,
    commitmentSignature: ByteStr,
    proofs: Proofs
) {
  def toTx: Either[ValidationError, CommitToGenerationTransaction] =
    for {
      _senderPk  <- PublicKey.fromBase58String(senderPublicKey)
      sig        <- BlsSignature(commitmentSignature)
      endorserPk <- BlsPublicKey(endorserPublicKey)
      t          <- CommitToGenerationTransaction.create(
        version.getOrElse(1.toByte),
        _senderPk,
        endorserPk,
        Height(generationPeriodStart),
        timestamp,
        fee,
        sig,
        proofs,
        AddressScheme.current.chainId
      )
    } yield t
}
