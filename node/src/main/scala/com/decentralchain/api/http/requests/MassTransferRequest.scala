package com.decentralchain.api.http.requests

import com.decentralchain.account.PublicKey
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.transfer.*
import com.decentralchain.transaction.transfer.MassTransferTransaction.Transfer
import com.decentralchain.transaction.{Asset, Proofs}
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

object MassTransferRequest {
  given Format[MassTransferRequest] = Format(
    (
      (JsPath \ "version").readNullable[Byte] and
        (JsPath \ "senderPublicKey").read[String] and
        (JsPath \ "assetId").readNullable[Asset] and
        (JsPath \ "transfers").read[List[Transfer]] and
        (JsPath \ "fee").read[Long] and
        (JsPath \ "timestamp").read[Long] and
        (JsPath \ "attachment").readWithDefault(ByteStr.empty) and
        (JsPath \ "proofs").readWithDefault(Proofs.empty)
    )(MassTransferRequest.apply),
    Json.writes[MassTransferRequest].transform((jsobj: JsObject) => jsobj + ("type" -> JsNumber(MassTransferTransaction.typeId.toInt)))
  )
}

case class MassTransferRequest(
    version: Option[Byte],
    senderPublicKey: String,
    assetId: Option[Asset],
    transfers: List[Transfer],
    fee: Long,
    timestamp: Long,
    attachment: ByteStr = ByteStr.empty,
    proofs: Proofs
) extends TxBroadcastRequest[MassTransferTransaction] {
  def toTx: Either[ValidationError, MassTransferTransaction] =
    for {
      _sender    <- PublicKey.fromBase58String(senderPublicKey)
      _transfers <- MassTransferTransaction.parseTransfersList(transfers)
      t <- MassTransferTransaction.create(
        version.getOrElse(1.toByte),
        _sender,
        assetId.getOrElse(Asset.Dcc),
        _transfers,
        fee,
        timestamp,
        attachment,
        proofs
      )
    } yield t
}
