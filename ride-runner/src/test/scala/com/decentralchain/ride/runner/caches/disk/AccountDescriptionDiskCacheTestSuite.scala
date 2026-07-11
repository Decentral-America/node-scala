package com.decentralchain.ride.runner.caches.disk

import com.decentralchain.common.state.ByteStr
import com.decentralchain.ride.runner.caches.WeighedAssetDescription
import com.decentralchain.ride.runner.db.{Heights, ReadOnly, ReadWrite}
import com.decentralchain.state.{AssetDescription, Height, TransactionId}
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.{Asset, AssetIdLength}
import com.decentralchain.utils.StringBytes

class AccountDescriptionDiskCacheTestSuite extends DiskCacheWithHistoryTestSuite[IssuedAsset, WeighedAssetDescription] {
  protected override val defaultKey   = Asset.IssuedAsset(ByteStr(Array.fill[Byte](AssetIdLength)(0)))
  protected override val defaultValue = WeighedAssetDescription(
    scriptWeight = 0,
    assetDescription = AssetDescription(
      originTransactionId = TransactionId(defaultKey.id),
      issuer = alice.publicKey,
      name = "name".toByteString,
      description = "description".toByteString,
      decimals = 8,
      reissuable = false,
      totalVolume = 1000,
      lastUpdatedAt = Height(0),
      script = None,
      sponsorship = 0,
      nft = false,
      sequenceInBlock = 0,
      issueHeight = Height(0)
    )
  )

  protected override def test(f: DiskCache[IssuedAsset, WeighedAssetDescription] => ReadWrite => Unit): Unit = withDb { db =>
    db.directReadWrite { implicit ctx =>
      f(DefaultDiskCaches(db).assetDescriptions)(ctx)
    }
  }

  override protected def getHistory(implicit ctx: ReadOnly): Heights =
    ctx
      .getOpt(KvPairs.AssetDescriptionsHistory.at(defaultKey))
      .getOrElse(Vector.empty)
}
