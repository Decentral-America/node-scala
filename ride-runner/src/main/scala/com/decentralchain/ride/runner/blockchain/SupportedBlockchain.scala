package com.decentralchain.ride.runner.blockchain

import com.decentralchain.account.Address
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.state.{AssetScriptInfo, Blockchain, Height, LeaseDetails, TxMeta, VolumeAndFee}
import com.decentralchain.transaction.transfer.TransferTransactionLike
import com.decentralchain.transaction.{Asset, ERC20Address, Transaction}
import com.decentralchain.utils.ScorexLogging

trait SupportedBlockchain extends Blockchain with ScorexLogging {
  // We don't support it for now (no demand), use GET /utils/script/evaluate if you need it.
  // Ride: isDataStorageUntouched
  override def hasData(address: Address): Boolean = kill(s"hasData($address)")

  // Ride: get*Value (data), get* (data)
  // override def accountData(address: Address, key: String): Option[DataEntry[?]]

  // Ride: scriptHash
  // override def accountScript(address: Address): Option[AccountScriptInfo]

  // Indirectly
  override def hasAccountScript(address: Address): Boolean = accountScript(address).nonEmpty

  // Ride: blockInfoByHeight, lastBlock
  // override def blockHeader(height: Int): Option[SignedBlockHeader]

  // Ride: blockInfoByHeight
  // override def hitSource(height: Int): Option[ByteStr]

  // Ride: blockInfoByHeight
  //  override def blockReward(height: Int): Option[Long] = kill("blockReward")

  // Ride: dccBalance, height, lastBlock
  // override def height: Int = sharedBlockchain.heightUntagged

  // override def activatedFeatures: Map[Short, Int]

  // Ride: assetInfo
  // override def assetDescription(id: Asset.IssuedAsset): Option[AssetDescription]

  // Ride (indirectly): asset script validation
  override def assetScript(id: Asset.IssuedAsset): Option[AssetScriptInfo] = assetDescription(id).flatMap(_.script)

  // Ride: get*Value (data), get* (data), isDataStorageUntouched, balance, scriptHash, dccBalance
  // override def resolveAlias(a: Alias): Either[ValidationError, Address]

  // Ride: dccBalance
  // override def leaseBalance(address: Address): LeaseBalance

  // Ride: assetBalance, dccBalance
  // override def balance(address: Address, mayBeAssetId: Asset): Long

  // Retrieves Dcc balance snapshot in the [from, to] range (inclusive)
  // Ride: dccBalance (specifies to=None), "to" always None and means "to the end"
  // override def balanceSnapshots(address: Address, from: Int, to: Option[BlockId]): Seq[BalanceSnapshot]

  // Ride: transactionHeightById
  // override def transactionMeta(id: ByteStr): Option[TxMeta]

  // Ride: transferTransactionById
  // We don't support this, because there is no demand.
  override def transferById(id: ByteStr): Option[(Int, TransferTransactionLike)] = kill("transferById")

  // Ride: transactionById
  // We don't support his, because 1) there is no demand 2) it works only for V1 and V2 scripts, see versionSpecificFuncs in DccContext
  override def transactionInfo(id: BlockId): Option[(TxMeta, Transaction)] = kill("transactionInfo")

  override def score: BigInt = kill("score")

  override def heightOf(blockId: ByteStr): Option[Int] = kill("heightOf")

  /** Features related */
  override def approvedFeatures: Map[Short, Height] = kill("approvedFeatures")

  override def featureVotes(height: Height): Map[Short, Int] = kill("featureVotes")

  override def containsTransaction(tx: Transaction): Boolean = kill("containsTransaction")

  override def leaseDetails(leaseId: ByteStr): Option[LeaseDetails] = kill("leaseDetails")

  override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee = kill("filledVolumeAndFee")

  override def blockRewardVotes(height: Int): Seq[Long] = kill("blockRewardVotes")

  override def dccAmount(height: Int): BigInt = kill("dccAmount")

  override def balanceAtHeight(address: Address, height: Int, assetId: Asset): Option[(Int, Long)] = kill("balanceAtHeight")

  // Not needed for now.
  // Return None, because it is used in AssetTransactionsDiff.issue, otherwise we can't issue assets in scripts.
  override def resolveERC20Address(address: ERC20Address): Option[Asset.IssuedAsset] = None

  private def kill(methodName: String) = throw new RuntimeException(s"$methodName is not supported, contact with developers")
}
