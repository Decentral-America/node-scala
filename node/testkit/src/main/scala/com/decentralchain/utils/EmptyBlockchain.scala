package com.decentralchain.utils

import com.typesafe.config.ConfigFactory
import com.decentralchain.account.{Address, Alias}
import com.decentralchain.block.SignedBlockHeader
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.BlsPublicKey
import com.decentralchain.lang.ValidationError
import com.decentralchain.settings.BlockchainSettings
import com.decentralchain.state.*
import com.decentralchain.state.TxMeta.Status
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.transfer.TransferTransactionLike
import com.decentralchain.transaction.{Asset, ERC20Address, Transaction}

trait EmptyBlockchain extends Blockchain {
  override lazy val settings: BlockchainSettings = BlockchainSettings.fromRootConfig(ConfigFactory.load())

  override def height: Int = GenesisBlockHeight.toInt

  override def finalizedHeight: Option[Height] = None

  override def finalizedHeightAt(at: Height): Option[Height] = None

  override def score: BigInt = 0

  override def blockHeader(height: Int): Option[SignedBlockHeader] = None

  override def hitSource(height: Int): Option[ByteStr] = None

  override def carryFee(refId: Option[ByteStr]): Long = 0

  override def heightOf(blockId: ByteStr): Option[Int] = None

  /** Features related */
  override def approvedFeatures: Map[Short, Height] = Map.empty

  override def activatedFeatures: Map[Short, Height] = Map.empty

  override def featureVotes(height: Height): Map[Short, Int] = Map.empty

  /** Block reward related */
  override def blockReward(height: Int): Option[Long] = None

  override def blockRewardVotes(height: Int): Seq[Long] = Seq.empty

  override def dccAmount(height: Int): BigInt = 0

  override def transferById(id: ByteStr): Option[(Int, TransferTransactionLike)] = None

  override def transactionInfo(id: ByteStr): Option[(TxMeta, Transaction)] = None

  override def transactionInfos(ids: Seq[ByteStr]): Seq[Option[(TxMeta, Transaction)]] = Seq.empty

  override def transactionMeta(id: ByteStr): Option[TxMeta] = None

  override def transactionSnapshot(id: ByteStr): Option[(StateSnapshot, Status)] = None

  override def containsTransaction(tx: Transaction): Boolean = false

  override def assetDescription(id: IssuedAsset): Option[AssetDescription] = None

  override def resolveAlias(a: Alias): Either[ValidationError, Address] = Left(GenericError("Empty blockchain"))

  override def leaseDetails(leaseId: ByteStr): Option[LeaseDetails] = None

  override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee = VolumeAndFee(0, 0)

  /** Retrieves Dcc balance snapshot in the [from, to] range (inclusive) */
  override def balanceAtHeight(address: Address, height: Int, assetId: Asset = Dcc): Option[(Int, Long)] = Option.empty
  override def balanceSnapshots(address: Address, from: Int, to: Option[ByteStr]): Seq[BalanceSnapshot]  = Seq.empty

  override def accountScript(address: Address): Option[AccountScriptInfo] = None

  override def hasAccountScript(address: Address): Boolean = false

  override def assetScript(asset: IssuedAsset): Option[AssetScriptInfo] = None

  override def accountData(acc: Address, key: String): Option[DataEntry[?]] = None

  override def hasData(acc: Address): Boolean = false

  override def balance(address: Address, mayBeAssetId: Asset): Long = 0

  override def balances(req: Seq[(Address, Asset)]): Map[(Address, Asset), Long] = Map.empty

  override def dccBalances(addresses: Seq[Address]): Map[Address, Long] = Map.empty

  override def effectiveBalanceBanHeights(address: Address): Seq[Int] = Seq.empty

  override def leaseBalance(address: Address): LeaseBalance = LeaseBalance.empty

  override def leaseBalances(addresses: Seq[Address]): Map[Address, LeaseBalance] = Map.empty

  override def resolveERC20Address(address: ERC20Address): Option[IssuedAsset] = None

  override def lastStateHash(refId: Option[ByteStr]): ByteStr = TxStateSnapshotHashBuilder.InitStateHash

  override def committedGenerators(at: GenerationPeriod): IndexedSeq[(Address, BlsPublicKey)] = IndexedSeq.empty

  override def conflictGenerators(at: GenerationPeriod): ConflictGenerators = ConflictGenerators.empty
}

object EmptyBlockchain extends EmptyBlockchain
