package com.decentralchain.ride.runner.caches.disk

import com.decentralchain.account.Address
import com.decentralchain.ride.runner.caches.*
import com.decentralchain.state.{DataEntry, Height, LeaseBalance}
import com.decentralchain.transaction.Asset
import com.decentralchain.transaction.Asset.IssuedAsset

trait DiskCaches {
  def blockHeaders: BlockDiskCache

  def accountDataEntries: DiskCache[(Address, String), DataEntry[?]]
  def accountScripts: DiskCache[Address, WeighedAccountScriptInfo]
  def assetDescriptions: DiskCache[IssuedAsset, WeighedAssetDescription]
  def aliases: AliasDiskCache
  def accountBalances: DiskCache[(Address, Asset), Long]
  def accountLeaseBalances: DiskCache[Address, LeaseBalance]
  def transactions: TransactionDiskCache

  def addressIds: AddressIdDiskCache

  def getActivatedFeatures(): RemoteData[Map[Short, Height]]
  def setActivatedFeatures(data: Map[Short, Height]): Unit
}
