package com.decentralchain.api.http.requests

import com.decentralchain.account.{AddressScheme, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.lang.script.Script
import com.decentralchain.transaction.assets.IssueTransaction
import com.decentralchain.transaction.{Proofs, TxVersion}
import play.api.libs.json.{Format, Json}

case class IssueRequest(
    version: Byte = TxVersion.V3,
    senderPublicKey: String,
    name: String,
    description: String,
    quantity: Long,
    decimals: Byte,
    reissuable: Boolean,
    script: Option[String],
    fee: Long,
    timestamp: Option[Long],
    signature: Option[ByteStr],
    proofs: Option[Proofs],
    chainId: Byte = AddressScheme.current.chainId
) extends TxBroadcastRequest[IssueTransaction] {
  def toTx: Either[ValidationError, IssueTransaction] = {
    for {
      validProofs <- toProofs(signature, proofs)
      validSender <- PublicKey.fromBase58String(senderPublicKey)
      validScript <- script match {
        case None         => Right(None)
        case Some(script) => Script.fromBase64String(script).map(Some(_))
      }
      tx <- IssueTransaction.create(
        version,
        validSender,
        name,
        description,
        quantity,
        decimals,
        reissuable,
        validScript,
        fee,
        timestamp.getOrElse(defaultTimestamp),
        validProofs,
        chainId
      )
    } yield tx
  }
}

object IssueRequest {
  given Format[IssueRequest] = Json.format
}
