package com.decentralchain.transaction

import com.decentralchain.transaction.Asset.Dcc

sealed trait TxWithFee {
  def fee: TxPositiveAmount
  def assetFee: (Asset, Long)
}

object TxWithFee {
  trait InDcc extends TxWithFee {
    override def assetFee: (Asset, Long) = (Dcc, fee.value)
  }

  trait InCustomAsset extends TxWithFee {
    def feeAssetId: Asset
    override def assetFee: (Asset, Long) = (feeAssetId, fee.value)
  }
}
