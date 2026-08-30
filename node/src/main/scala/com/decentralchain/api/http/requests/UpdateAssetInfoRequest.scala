package com.decentralchain.api.http.requests

import cats.instances.option.*
import cats.syntax.traverse.*
import com.decentralchain.account.PublicKey
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.assets.UpdateAssetInfoTransaction
import com.decentralchain.transaction.{AssetIdStringLength, Proofs, TxTimestamp, TxVersion}
import play.api.libs.json.{Json, OFormat}

case class UpdateAssetInfoRequest(
    version: TxVersion,
    chainId: Byte,
    sender: Option[String],
    senderPublicKey: Option[String],
    assetId: String,
    name: String,
    description: String,
    timestamp: Option[TxTimestamp],
    fee: Long,
    feeAssetId: Option[String],
    proofs: Option[Proofs]
) extends TxBroadcastRequest[UpdateAssetInfoTransaction] {
  override def toTxFrom(sender: PublicKey): Either[ValidationError, UpdateAssetInfoTransaction] =
    for {
      _assetId    <- parseBase58(assetId, "invalid.assetId", AssetIdStringLength)
      _feeAssetId <- feeAssetId
        .traverse(parseBase58(_, "invalid.assetId", AssetIdStringLength).map(IssuedAsset(_)))
        .map(_ getOrElse Dcc)
      tx <- UpdateAssetInfoTransaction
        .create(
          version,
          sender,
          IssuedAsset(_assetId),
          name,
          description,
          timestamp.getOrElse(0L),
          fee,
          _feeAssetId,
          proofs.getOrElse(Proofs.empty),
          chainId
        )
    } yield tx
}

object UpdateAssetInfoRequest {
  implicit val jsonFormat: OFormat[UpdateAssetInfoRequest] = Json.format[UpdateAssetInfoRequest]
}
