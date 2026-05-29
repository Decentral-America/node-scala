package com.decentralchain.ride.runner.environments

import com.decentralchain.account.Address
import com.decentralchain.common.state.ByteStr
import com.decentralchain.ride.runner.caches.CacheKeyTags
import com.decentralchain.ride.runner.caches.mem.MemCacheKey
import com.decentralchain.state.TransactionId
import com.decentralchain.transaction.Asset
import com.decentralchain.utils.ScorexLogging

class DefaultDAppEnvironmentTracker[TagT](allTags: CacheKeyTags[TagT], tag: TagT) extends DAppEnvironmentTracker with ScorexLogging {
  override def height(): Unit = {
    // log.trace(s"[$tag] height")
    allTags.addDependent(MemCacheKey.Height, tag)
  }

  override def lastBlockOpt(): Unit = {
    // log.trace(s"[$tag] lastBlockOpt")
    height()
  }

  // We don't support this, see SupportedBlockchain.transactionInfo
  override def transactionById(id: Array[Byte]): Unit = {
    // val txId = mkTxCacheKey(id)
    // log.trace(s"[$tag] transactionById($txId)")
    // allTags.addDependent(txId, tag)
  }

  // We don't support this, see SupportedBlockchain.transferById
  override def transferTransactionById(id: Array[Byte]): Unit = {
    // val txId = mkTxCacheKey(id)
    // log.trace(s"[$tag] transferTransactionById($txId)")
    // allTags.addDependent(txId, tag)
  }

  override def transactionHeightById(id: Array[Byte]): Unit = {
    val txId = mkTxCacheKey(id)
    // log.trace(s"[$tag] transactionHeightById($txId)")
    allTags.addDependent(txId, tag)
  }

  private def mkTxCacheKey(txId: Array[Byte]) = MemCacheKey.Transaction(TransactionId(ByteStr(txId)))

  override def assetInfoById(id: Array[Byte]): Unit = {
    val asset = Asset.IssuedAsset(ByteStr(id))
    // log.trace(s"[$tag] transactionHeightById($asset)")
    allTags.addDependent(MemCacheKey.Asset(asset), tag)
  }

  override def blockInfoByHeight(height: Int): Unit = {
    // log.trace(s"[$tag] blockInfoByHeight($height)")
    // NOTE: Script usage is unknown at this point — both blockInfoByHeight and lastBlock may be used
    // So we will force update the scripts those use one (or both) of these functions.
    // If this will be an issue, consider two different cases for H (height),
    // where H < currHeight - 100 or H >= currHeight - 100.
    allTags.addDependent(MemCacheKey.Height, tag)
  }

  override def data(address: Address, key: String): Unit = {
    // log.trace(s"[$tag] data($address, $key)")
    allTags.addDependent(MemCacheKey.AccountData(address, key), tag)
  }

  // We don't support it for now because of no demand. Use GET /utils/script/evaluate if needed.
  override def hasData(address: Address): Unit = {}

  override def resolveAlias(name: String): Unit = {
    // log.trace(s"[$tag] resolveAlias($name)")
    com.decentralchain.account.Alias.create(name).foreach(x => allTags.addDependent(MemCacheKey.Alias(x), tag))
  }

  override def accountBalanceOf(address: Address, assetId: Option[Array[Byte]]): Unit = {
    val asset = Asset.fromCompatId(assetId.map(ByteStr(_)))
    // log.trace(s"[$tag] accountBalanceOf($address, $asset)")
    allTags.addDependent(MemCacheKey.AccountBalance(address, asset), tag)
  }

  override def accountDccBalanceOf(address: Address): Unit = {
    // log.trace(s"[$tag] accountDccBalanceOf($address)")
    allTags.addDependent(MemCacheKey.AccountBalance(address, Asset.Dcc), tag)
    allTags.addDependent(MemCacheKey.AccountLeaseBalance(address), tag)
  }

  override def accountScript(address: Address): Unit = {
    // log.trace(s"[$tag] accountScript($address)")
    allTags.addDependent(MemCacheKey.AccountScript(address), tag)
  }

  override def callScript(dApp: Address): Unit = {
    // log.trace(s"[$tag] callScript($address)")
    accountScript(dApp)
  }
}
