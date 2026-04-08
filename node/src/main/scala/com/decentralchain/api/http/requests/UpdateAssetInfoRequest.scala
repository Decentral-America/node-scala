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
    senderPublicKey: String,
    assetId: IssuedAsset,
    name: String,
    description: String,
    timestamp: Option[TxTimestamp],
    fee: Long,
    feeAssetId: Option[String],
    proofs: Option[Proofs]
) extends TxBroadcastRequest[UpdateAssetInfoTransaction] {
  override def toTx: Either[ValidationError, UpdateAssetInfoTransaction] =
    for {
      _feeAssetId <- feeAssetId
        .traverse(parseBase58(_, "invalid.assetId", AssetIdStringLength).map(IssuedAsset(_)))
        .map(_ getOrElse Waves)
      _sender <- PublicKey.fromBase58String(senderPublicKey)
      tx <- UpdateAssetInfoTransaction
        .create(version, _sender, assetId, name, description, timestamp.getOrElse(0L), fee, _feeAssetId, proofs.getOrElse(Proofs.empty), chainId)
    } yield tx
}

object UpdateAssetInfoRequest {
  given OFormat[UpdateAssetInfoRequest] = Json.format[UpdateAssetInfoRequest]
}
