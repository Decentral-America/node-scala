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
  def toTxFrom(
      senderPk: PublicKey,
      defaultEndorserKp: => BlsKeyPair,
      defaultGenerationPeriodStart: Height,
      defaultTimestamp: => Long
  ): Either[ValidationError, CommitToGenerationTransaction] = {
    val exactGenerationPeriodStart = generationPeriodStart.getOrElse(defaultGenerationPeriodStart)
    for {
      commitmentSignature <- commitmentSignature match {
        case Some(r) => BlsSignature(r)
        case None    => Right(CommitToGenerationTransaction.mkPopSignature(defaultEndorserKp, exactGenerationPeriodStart))
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
