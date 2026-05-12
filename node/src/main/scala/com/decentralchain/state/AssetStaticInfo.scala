package com.decentralchain.state

import com.decentralchain.account.PublicKey
import com.decentralchain.common.state.ByteStr
import play.api.libs.json.{Format, Json, OWrites}

case class AssetStaticInfo(id: ByteStr, source: TransactionId, issuer: PublicKey, decimals: Int, nft: Boolean)

object AssetStaticInfo {
  implicit val byteStrFormat: Format[ByteStr]   = com.decentralchain.utils.byteStrFormat
  implicit val format: OWrites[AssetStaticInfo] = Json.writes[AssetStaticInfo]
}
