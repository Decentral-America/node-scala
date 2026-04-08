package com.decentralchain.api.http.requests

import com.decentralchain.account.{AddressOrAlias, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.transfer.TransferTransaction
import com.decentralchain.transaction.{Asset, Proofs}
import play.api.libs.json.*

case class TransferRequest(
    version: Byte = 1.toByte,
    senderPublicKey: String,
    recipient: String,
    assetId: Option[Asset],
    amount: Long,
    feeAssetId: Option[Asset],
    fee: Long,
    attachment: Option[ByteStr] = None,
    timestamp: Option[Long] = None,
    signature: Option[ByteStr] = None,
    proofs: Option[Proofs] = None
) extends TxBroadcastRequest[TransferTransaction] {
  def toTx: Either[ValidationError, TransferTransaction] =
    for {
      validRecipient <- AddressOrAlias.fromString(recipient)
      validProofs    <- toProofs(signature, proofs)
      validSender    <- PublicKey.fromBase58String(senderPublicKey)
      tx <- TransferTransaction.create(
        version,
        validSender,
        validRecipient,
        assetId.getOrElse(Asset.Dcc),
        amount,
        feeAssetId.getOrElse(Asset.Dcc),
        fee,
        attachment.getOrElse(ByteStr.empty),
        timestamp.getOrElse(0L),
        validProofs
      )
    } yield tx
}

object TransferRequest {
  given Format[TransferRequest] = Json.format
}
