package com.decentralchain.api.common

import com.decentralchain.account.Address
import com.decentralchain.api.common.CommonAssetsApi.AssetInfo
import com.decentralchain.crypto
import com.decentralchain.database.{AddressId, KeyTag}
import com.decentralchain.state.{AssetDescription, Blockchain, StateSnapshot, TxMeta}
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.assets.IssueTransaction
import monix.reactive.Observable
import org.rocksdb.RocksDB

trait CommonAssetsApi {
  def description(assetId: IssuedAsset): Option[AssetDescription]

  def fullInfo(assetId: IssuedAsset): Option[AssetInfo]

  def fullInfos(assetIds: Seq[IssuedAsset]): Seq[Option[AssetInfo]]

  def dccDistribution(height: Int, after: Option[Address]): Observable[(Address, Long)]

  def assetDistribution(asset: IssuedAsset, height: Int, after: Option[Address]): Observable[(Address, Long)]
}

object CommonAssetsApi {
  final case class AssetInfo(description: AssetDescription, issueTransaction: Option[IssueTransaction], sponsorBalance: Option[Long])

  def apply(snapshot: () => StateSnapshot, db: RocksDB, blockchain: Blockchain): CommonAssetsApi = new CommonAssetsApi {
    def description(assetId: IssuedAsset): Option[AssetDescription] =
      blockchain.assetDescription(assetId)

    def fullInfo(assetId: IssuedAsset): Option[AssetInfo] =
      for {
        assetInfo <- blockchain.assetDescription(assetId)
        sponsorBalance = if (assetInfo.sponsorship != 0) Some(blockchain.dccPortfolio(assetInfo.issuer.toAddress).spendableBalance) else None
      } yield AssetInfo(
        assetInfo,
        blockchain.transactionInfo(assetId.id).collect { case (tm, it: IssueTransaction) if tm.status == TxMeta.Status.Succeeded => it },
        sponsorBalance
      )

    override def fullInfos(assetIds: Seq[IssuedAsset]): Seq[Option[AssetInfo]] = {
      blockchain
        .transactionInfos(assetIds.map(_.id))
        .view
        .zip(assetIds)
        .map { case (tx, assetId) =>
          blockchain.assetDescription(assetId).map { desc =>
            AssetInfo(
              desc,
              tx.collect { case (tm, it: IssueTransaction) if tm.status == TxMeta.Status.Succeeded => it },
              if (desc.sponsorship != 0) Some(blockchain.dccPortfolio(desc.issuer.toAddress).spendableBalance) else None
            )
          }
        }
        .toSeq
    }

    override def dccDistribution(height: Int, after: Option[Address]): Observable[(Address, Long)] =
      balanceDistribution(
        db,
        height,
        after,
        if (height == blockchain.height) snapshot().balances else Map(),
        KeyTag.DccBalanceHistory.prefixBytes,
        bs => AddressId.fromByteArray(bs.slice(2, bs.length - 4)),
        Dcc
      )

    override def assetDistribution(asset: IssuedAsset, height: Int, after: Option[Address]): Observable[(Address, Long)] =
      balanceDistribution(
        db,
        height,
        after,
        if (height == blockchain.height) snapshot().balances else Map(),
        KeyTag.AssetBalanceHistory.prefixBytes ++ asset.id.arr,
        bs => AddressId.fromByteArray(bs.slice(2 + crypto.DigestLength, bs.length - 4)),
        asset
      )
  }
}
