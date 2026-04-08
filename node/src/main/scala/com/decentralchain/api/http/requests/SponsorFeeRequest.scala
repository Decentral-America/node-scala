package com.decentralchain.api.http.requests

import com.decentralchain.account.{AddressScheme, PublicKey}
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.Proofs
import com.decentralchain.transaction.assets.SponsorFeeTransaction
import play.api.libs.json.{Format, Json}

object SponsorFeeRequest {
  given Format[SponsorFeeRequest] = Json.format
}

case class SponsorFeeRequest(
    version: Byte = 1.toByte,
    senderPublicKey: String,
    assetId: IssuedAsset,
    minSponsoredAssetFee: Option[Long],
    fee: Long,
    timestamp: Long,
    proofs: Proofs = Proofs.empty,
    chainId: Byte = AddressScheme.current.chainId
) extends TxBroadcastRequest[SponsorFeeTransaction] {
  def toTx: Either[ValidationError, SponsorFeeTransaction] =
    for {
      validSender <- PublicKey.fromBase58String(senderPublicKey)
      t <- SponsorFeeTransaction.create(version, validSender, assetId, minSponsoredAssetFee.filterNot(_ == 0), fee, timestamp, proofs, chainId)
    } yield t
}
