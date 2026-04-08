package com.decentralchain.api.http.requests

import com.decentralchain.account.{AddressScheme, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.{CreateAliasTransaction, Proofs, TxTimestamp, TxVersion}
import play.api.libs.json.{Format, Json}

case class CreateAliasRequest(
    alias: String,
    version: TxVersion = 1.toByte,
    senderPublicKey: String,
    fee: Option[Long] = None,
    timestamp: Option[TxTimestamp] = None,
    signature: Option[ByteStr] = None,
    proofs: Option[Proofs] = None,
    chainId: Byte = AddressScheme.current.chainId
) extends TxBroadcastRequest[CreateAliasTransaction] {
  def toTx: Either[ValidationError, CreateAliasTransaction] =
    for {
      validProofs <- toProofs(signature, proofs)
      validSender <- PublicKey.fromBase58String(senderPublicKey)
      tx          <- CreateAliasTransaction.create(version, validSender, alias, fee.getOrElse(0L), timestamp.getOrElse(0L), validProofs, chainId)
    } yield tx
}

object CreateAliasRequest {
  given Format[CreateAliasRequest] = Json.format
}
