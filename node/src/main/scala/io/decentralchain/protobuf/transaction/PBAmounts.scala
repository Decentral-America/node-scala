package io.decentralchain.protobuf.transaction

import com.google.protobuf.ByteString
import io.decentralchain.protobuf.*
import com.decentralchain.transaction.Asset
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}

object PBAmounts {
  def toPBAssetId(asset: Asset): ByteString = asset match {
    case Asset.IssuedAsset(id) => id.toByteString
    case Asset.Dcc             => ByteString.EMPTY
  }

  def toVanillaAssetId(byteStr: ByteString): Asset = {
    if (byteStr.isEmpty) Dcc
    else IssuedAsset(byteStr.toByteStr)
  }

  def fromAssetAndAmount(asset: Asset, amount: Long): Amount =
    Amount(toPBAssetId(asset), amount)

  def toAssetAndAmount(value: Amount): (Asset, Long) =
    (toVanillaAssetId(value.assetId), value.amount)
}
