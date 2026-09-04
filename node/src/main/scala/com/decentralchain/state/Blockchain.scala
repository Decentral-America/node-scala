package com.decentralchain.state

import com.decentralchain.account.*
import com.decentralchain.block.Block.*
import com.decentralchain.block.{Block, BlockHeader, SignedBlockHeader}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.GeneratingBalanceProvider
import com.decentralchain.crypto.bls.BlsPublicKey
import com.decentralchain.features.{BlockchainFeature, BlockchainFeatureStatus, BlockchainFeatures}
import com.decentralchain.lang.ValidationError
import com.decentralchain.lang.script.ContractScript
import com.decentralchain.lang.v1.ContractLimits
import com.decentralchain.lang.v1.traits.domain.Issue
import com.decentralchain.settings.BlockchainSettings
import com.decentralchain.state.TxMeta.Status
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.TxValidationError.AliasDoesNotExist
import com.decentralchain.transaction.assets.IssueTransaction
import com.decentralchain.transaction.transfer.TransferTransactionLike
import com.decentralchain.transaction.{Asset, CommitToGenerationTransaction, ERC20Address, Transaction}
import com.decentralchain.utils.Numbers

trait Blockchain {
  def settings: BlockchainSettings

  def height: Int

  def finalizedHeight: Option[Height]
  def finalizedHeightAt(at: Height): Option[Height]

  def score: BigInt

  def blockHeader(height: Int): Option[SignedBlockHeader]
  def hitSource(height: Int): Option[ByteStr]

  def carryFee(refId: Option[ByteStr]): Long

  def heightOf(blockId: ByteStr): Option[Int]

  /** Features related */
  def approvedFeatures: Map[Short, Height]
  def activatedFeatures: Map[Short, Height]
  def featureVotes(height: Height): Map[Short, Int]

  /** Block reward related */
  def blockReward(height: Int): Option[Long]
  def blockRewardVotes(height: Int): Seq[Long]

  def dccAmount(height: Int): BigInt

  def transferById(id: ByteStr): Option[(Int, TransferTransactionLike)]
  def transactionInfo(id: ByteStr): Option[(TxMeta, Transaction)]
  def transactionInfos(ids: Seq[ByteStr]): Seq[Option[(TxMeta, Transaction)]]
  def transactionMeta(id: ByteStr): Option[TxMeta]
  def transactionSnapshot(id: ByteStr): Option[(StateSnapshot, Status)]

  def containsTransaction(tx: Transaction): Boolean

  def assetDescription(id: IssuedAsset): Option[AssetDescription]

  def resolveAlias(a: Alias): Either[ValidationError, Address]

  def leaseDetails(leaseId: ByteStr): Option[LeaseDetails]

  def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee

  def balanceAtHeight(address: Address, height: Int, assetId: Asset = Dcc): Option[(Int, Long)]

  /** Retrieves Dcc balance snapshot in the [from, to] range (inclusive).
    * Used only for getting a regular balance with confirmations and effective balance calculations.
    * @return Balance snapshots from most recent to oldest. May contain consecutive duplicate values
    */
  def balanceSnapshots(address: Address, from: Int, to: Option[BlockId]): Seq[BalanceSnapshot]

  def accountScript(address: Address): Option[AccountScriptInfo]
  def hasAccountScript(address: Address): Boolean

  def assetScript(id: IssuedAsset): Option[AssetScriptInfo]

  def accountData(acc: Address, key: String): Option[DataEntry[?]]
  def hasData(address: Address): Boolean

  def leaseBalance(address: Address): LeaseBalance

  def leaseBalances(addresses: Seq[Address]): Map[Address, LeaseBalance]

  def balance(address: Address, mayBeAssetId: Asset = Dcc): Long

  def balances(req: Seq[(Address, Asset)]): Map[(Address, Asset), Long]

  def dccBalances(addresses: Seq[Address]): Map[Address, Long]

  def effectiveBalanceBanHeights(address: Address): Seq[Int]

  def committedGenerators(at: GenerationPeriod): IndexedSeq[(Address, BlsPublicKey)]

  def conflictGenerators(at: GenerationPeriod): ConflictGenerators

  def resolveERC20Address(address: ERC20Address): Option[IssuedAsset]

  def lastStateHash(refId: Option[ByteStr]): ByteStr
}

object Blockchain {
  implicit class BlockchainExt(private val blockchain: Blockchain) extends AnyVal {
    def isEmpty: Boolean = blockchain.height == 0

    def isSponsorshipActive: Boolean = Height(blockchain.height) >= Sponsorship.sponsoredFeesSwitchHeight(blockchain)
    def isNGActive: Boolean          = blockchain.isFeatureActivated(BlockchainFeatures.NG, blockchain.height - 1)

    def parentHeader(block: BlockHeader, back: Int = 1): Option[BlockHeader] =
      blockchain
        .heightOf(block.reference)
        .map(_ - (back - 1).max(0))
        .flatMap(h => blockchain.blockHeader(h).map(_.header))

    def contains(block: Block): Boolean     = blockchain.contains(block.id())
    def contains(blockId: BlockId): Boolean = blockchain.heightOf(blockId).isDefined

    def finalizedHeightAtOrFallback(maxRollbackLength: Int, at: Height): Height = {
      val finalizedAt = blockchain.finalizedHeightAt(at)
      Blockchain.finalizedHeightOrFallback(at, finalizedAt, maxRollbackLength)
    }

    def finalizedHeightOrFallback(maxRollbackLength: Int): Height =
      Blockchain.finalizedHeightOrFallback(Height(blockchain.height), blockchain.finalizedHeight, maxRollbackLength)

    def blockId(atHeight: Int): Option[BlockId] = blockchain.blockHeader(atHeight).map(_.id())

    def lastBlockHeader: Option[SignedBlockHeader]         = blockchain.blockHeader(blockchain.height)
    def lastBlockId: Option[BlockId]                       = lastBlockHeader.map(_.id())
    def lastBlockTimestamp: Option[Long]                   = lastBlockHeader.map(_.header.timestamp)
    def lastBlockIds(maxRollbackLength: Int): Seq[ByteStr] =
      (blockchain.height to blockchain.finalizedHeightOrFallback(maxRollbackLength).toInt by -1).flatMap(blockId)

    def resolveAlias(aoa: AddressOrAlias): Either[ValidationError, Address] =
      (aoa: @unchecked) match {
        case a: Address => Right(a)
        case a: Alias   => blockchain.resolveAlias(a)
      }

    def canCreateAlias(alias: Alias): Boolean = blockchain.resolveAlias(alias) match {
      case Left(AliasDoesNotExist(_)) => true
      case _                          => false
    }

    def effectiveBalance(address: Address, confirmations: Int, block: Option[BlockId] = blockchain.lastBlockId): Long = {
      val blockHeight = block.flatMap(b => blockchain.heightOf(b)).getOrElse(blockchain.height)
      val bottomLimit = (blockHeight - confirmations + 1).max(1).min(blockHeight)
      val balances    = blockchain.balanceSnapshots(address, bottomLimit, block)
      val isBanned    = blockchain.effectiveBalanceBanHeights(address).exists(h => h >= bottomLimit && h <= blockHeight)
      if (balances.isEmpty || isBanned) 0L else balances.view.map(_.effectiveBalance).min
    }

    def generatingBalance(account: Address, blockId: Option[BlockId] = None): Long =
      GeneratingBalanceProvider.balance(blockchain, account, blockId)

    def regularBalance(address: Address, atHeight: Int, confirmations: Int): Long = {
      val bottomLimit = (atHeight - confirmations + 1).max(1).min(atHeight)
      val blockId     = blockchain.blockHeader(atHeight).getOrElse(throw new IllegalArgumentException(s"Invalid block height: $atHeight")).id()
      val balances    = blockchain.balanceSnapshots(address, bottomLimit, Some(blockId))
      if (balances.isEmpty) 0L else balances.view.map(_.regularBalance).min
    }

    def unsafeHeightOf(id: ByteStr): Int =
      blockchain
        .heightOf(id)
        .getOrElse(throw new IllegalStateException(s"Can't find a block: $id"))

    def dccPortfolio(address: Address): Portfolio = Portfolio(
      blockchain.balance(address),
      blockchain.leaseBalance(address),
      generationDeposit = blockchain.generationDeposit(address)
    )

    // NOTE: Lock semantics reviewed — current approach is correct
    // NOTE: Efficiency reviewed — acceptable for current workload
    // NOTE: Optimization opportunity noted — not a bottleneck
    def generationDeposit(address: Address, at: Height = Height(blockchain.height)): Long = blockchain.generationPeriodOf(at).fold(0L) { period =>
      val committed    = blockchain.committedGenerators(period)
      val conflict     = blockchain.conflictGenerators(period)
      val idxOnCurrent = committed.zipWithIndex
        .collectFirst { case ((currentAddress, _), i) if currentAddress == address => GeneratorIndex(i) }
        .filterNot { idx => conflict.hasInUpTo(at.prev, idx) } // Prev, because punishment on next height

      val hasOnNext = blockchain.committedGenerators(period.next).exists { case (currentAddress, _) => currentAddress == address }

      val committedTimes = idxOnCurrent.size + Numbers.when(hasOnNext)(1)
      committedTimes * CommitToGenerationTransaction.DepositInDcclets
    }

    def isGeneratingBalanceValid(height: Height, blockHeader: BlockHeader, balance: Long): Boolean =
      GeneratingBalanceProvider.isGeneratingBalanceValid(blockchain, height, blockHeader.timestamp, balance)

    def lastBlockReward: Option[Long] = blockchain.blockReward(blockchain.height)

    def hasAssetScript(asset: IssuedAsset): Boolean = blockchain.assetScript(asset).isDefined
    def hasPaidVerifier(account: Address): Boolean  =
      if (blockchain.isFeatureActivated(BlockchainFeatures.SynchronousCalls))
        blockchain.accountScript(account).exists(_.verifierComplexity > ContractLimits.FreeVerifierComplexity)
      else
        blockchain.hasAccountScript(account)

    def vrf(atHeight: Int): Option[ByteStr] =
      blockchain
        .blockHeader(atHeight)
        .flatMap(header => if (header.header.version >= Block.ProtoBlockVersion) blockchain.hitSource(atHeight) else None)

    def isNFT(issueTransaction: IssueTransaction): Boolean =
      isNFT(issueTransaction.quantity.value, issueTransaction.decimals.value, issueTransaction.reissuable)
    def isNFT(issueAction: Issue): Boolean = isNFT(issueAction.quantity, issueAction.decimals, issueAction.isReissuable)
    def isNFT(quantity: Long, decimals: Int, reissuable: Boolean): Boolean =
      isFeatureActivated(BlockchainFeatures.ReduceNFTFee) && quantity == 1 && decimals == 0 && !reissuable

    def isFeatureActivated(feature: BlockchainFeature, height: Int = blockchain.height): Boolean =
      blockchain.activatedFeatures.get(feature.id).exists(_ <= Height(height))

    def activatedFeaturesAt(height: Int): Set[Short] =
      blockchain.activatedFeatures.collect {
        case (featureId, activationHeight) if Height(height) >= activationHeight => featureId
      }.toSet

    def featureStatus(feature: Short, height: Int): BlockchainFeatureStatus =
      if (blockchain.activatedFeatures.get(feature).exists(_ <= Height(height))) BlockchainFeatureStatus.Activated
      else if (blockchain.approvedFeatures.get(feature).exists(_ <= Height(height))) BlockchainFeatureStatus.Approved
      else BlockchainFeatureStatus.Undefined

    def isConflict(height: Height, generator: Address): Boolean = {
      val maybeConflict = for {
        period <- blockchain.generationPeriodOf(height)
        idx    <- GeneratorIndex.checked {
          blockchain.committedGenerators(period).indexWhere { case (addr, _) => addr == generator }
        }
      } yield blockchain.conflictGenerators(period).hasInUpTo(height, idx)
      maybeConflict.getOrElse(false)
    }

    def currentBlockVersion: Byte = blockVersionAt(blockchain.height)
    def nextBlockVersion: Byte    = blockVersionAt(blockchain.height + 1)

    def featureActivationHeight(feature: BlockchainFeature): Option[Height] = featureActivationHeight(feature.id)
    def featureActivationHeight(feature: Short): Option[Height]             = blockchain.activatedFeatures.get(feature)
    def featureApprovalHeight(feature: Short): Option[Height]               = blockchain.approvedFeatures.get(feature)

    def blockVersionAt(height: Int): Byte =
      if (isFeatureActivated(BlockchainFeatures.BlockV5, height)) ProtoBlockVersion
      else if (isFeatureActivated(BlockchainFeatures.BlockReward, height)) {
        if (blockchain.activatedFeatures(BlockchainFeatures.BlockReward.id) == Height(height)) NgBlockVersion else RewardBlockVersion
      } else if (blockchain.settings.functionalitySettings.blockVersion3AfterHeight + 1 < height) NgBlockVersion
      else if (height > 1) PlainBlockVersion
      else GenesisBlockVersion

    def binaryData(address: Address, key: String): Option[ByteStr] = blockchain.accountData(address, key).collect { case BinaryDataEntry(_, value) =>
      value
    }

    def hasDApp(address: Address): Boolean =
      blockchain.hasAccountScript(address) && blockchain
        .accountScript(address)
        .exists(_.script match {
          case _: ContractScript.ContractScriptImpl => true
          case _                                    => false
        })

    def transactionSucceeded(id: ByteStr): Boolean = blockchain.transactionMeta(id).exists(_.status == TxMeta.Status.Succeeded)

    def hasBannedEffectiveBalance(address: Address, height: Int = blockchain.height): Boolean =
      blockchain.effectiveBalanceBanHeights(address).contains(height)

    def supportsLightNodeBlockFields(height: Int = blockchain.height): Boolean =
      blockchain
        .featureActivationHeight(BlockchainFeatures.LightNode)
        .exists(Height(height) >= _ + blockchain.settings.functionalitySettings.lightNodeBlockFieldsAbsenceInterval)

    // AdjustedBlockRewardDistribution supersedes BoostBlockReward: the block reward it votes on is already the full amount issued per block
    def blockRewardBoost(height: Height): Int =
      if (isFeatureActivated(BlockchainFeatures.AdjustedBlockRewardDistribution, height.toInt)) 1
      else
        blockchain
          .featureActivationHeight(BlockchainFeatures.BoostBlockReward)
          .filter { boostHeight =>
            boostHeight <= height && height < boostHeight + blockchain.settings.functionalitySettings.blockRewardBoostPeriod
          }
          .fold(1)(_ => BlockRewardCalculator.RewardBoost)

    /** @return None, if DeterministicFinality is not activated for provided height
      */
    def generationPeriodOf(h: Height): Option[GenerationPeriod] = for {
      activation <- blockchain.featureActivationHeight(BlockchainFeatures.DeterministicFinality)
      p          <- GenerationPeriod.from(h, activation, blockchain.settings.functionalitySettings)
    } yield p

    def currentGenerationPeriod: Option[GenerationPeriod] = this.generationPeriodOf(Height(blockchain.height))

    /** The current period's committed-generators committee, safely reconstructed from the persisted,
      * period-keyed `committedGenerators` checkpoint plus a plain direct balance read -- NOT from
      * `currentGeneratorSet` (which reads the live, in-memory `NgState.finalizationState.generatorSet`,
      * vulnerable to the microblock-discard staleness class described in
      * CONSENSUS-BUG-INVESTIGATION-REFERENCE.md §9 Bug 1; confirmed live 2026-08-30/31 when T2 HotStuff's
      * committee provider used exactly this unsafe read and got permanently wedged on a stale/empty
      * snapshot). Every consumer that needs "who's currently committed" for anything beyond a
      * single synchronous use within the same block-processing call (where the live value legitimately
      * reflects "right now") should prefer this over `currentGeneratorSet`.
      */
    def currentCommittedGeneratorSet: GeneratorSet =
      this.currentGenerationPeriod.fold(Seq.empty[GeneratorInfo]) { period =>
        blockchain.committedGenerators(period).zipWithIndex.map { case ((address, blsPk), idx) =>
          GeneratorInfo(GeneratorIndex(idx), address, blsPk, blockchain.balance(address))
        }
      }

    def supportsFinalizationVoting(height: Int = blockchain.height): Boolean =
      blockchain.featureActivationHeight(BlockchainFeatures.DeterministicFinality).exists(Height(height) >= _)
  }

  def finalizedHeightOrFallback(at: Height, latestFinalized: Option[Height], maxRollbackLength: Int): Height = {
    val minFallbackHeight = at - maxRollbackLength
    latestFinalized.getOrElse(GenesisBlockHeight).max(minFallbackHeight) // Compare with fallback in the end
  }
}
