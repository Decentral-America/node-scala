package com.decentralchain.api.http.requests

import com.decentralchain.account.PublicKey
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.Proofs
import com.decentralchain.transaction.assets.ReissueTransaction
import play.api.libs.json.{Format, Json}

case class ReissueRequest(
    version: Option[Byte],
    senderPublicKey: String,
    assetId: IssuedAsset,
    quantity: Long,
    reissuable: Boolean,
    fee: Long,
    timestamp: Option[Long],
    signature: Option[ByteStr],
    proofs: Option[Proofs]
) extends TxBroadcastRequest[ReissueTransaction] {
  def toTx: Either[ValidationError, ReissueTransaction] =
    for {
      validProofs <- toProofs(signature, proofs)
      validSender <- PublicKey.fromBase58String(senderPublicKey)
      tx <- ReissueTransaction.create(
        version.getOrElse(defaultVersion),
        validSender,
        assetId,
        quantity,
        reissuable,
        fee,
        timestamp.getOrElse(defaultTimestamp),
        validProofs
      )
    } yield tx
}

object ReissueRequest {
  given Format[ReissueRequest] = Json.format
}
