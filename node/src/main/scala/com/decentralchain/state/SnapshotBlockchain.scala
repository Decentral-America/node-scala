package com.decentralchain.state

import cats.syntax.option.*
import com.decentralchain.account.{Address, Alias, PublicKey}
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.{Block, SignedBlockHeader}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.BlsPublicKey
import com.decentralchain.features.BlockchainFeatures.RideV6
import com.decentralchain.lang.ValidationError
import com.decentralchain.settings.BlockchainSettings
import com.decentralchain.state.TxMeta.Status
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.TxValidationError.{AliasDoesNotExist, AliasIsDisabled}
import com.decentralchain.transaction.transfer.{TransferTransaction, TransferTransactionLike}
import com.decentralchain.transaction.{Asset, CommitToGenerationTransaction, ERC20Address, Transaction}

case class SnapshotBlockchain(
    inner: Blockchain,
    maybeSnapshot: Option[StateSnapshot] = None,
    blockMeta: Option[(SignedBlockHeader, ByteStr)] = None,
    carry: Long = 0,
    reward: Option[Long] = None,
    stateHash: Option[ByteStr] = None,
    latestGeneratorSet: Option[GeneratorSet] = None
) extends Blockchain {
  override val settings: BlockchainSettings = inner.settings
  lazy val snapshot: StateSnapshot          = maybeSnapshot.orEmpty

  override def balance(address: Address, assetId: Asset): Long =
    snapshot.balances.getOrElse((address, assetId), inner.balance(address, assetId))

  override def balances(req: Seq[(Address, Asset)]): Map[(Address, Asset), Long] = {
    val (innerBalances, snapshotBalances) = req
      .foldLeft((Seq[(Address, Asset)](), Map[(Address, Asset), Long]())) { case ((innerBalances, snapshotBalances), key) =>
        snapshot.balances
          .get(key)
          .fold(
            (innerBalances :+ key, snapshotBalances)
          )(balance => (innerBalances, snapshotBalances + (key -> balance)))
      }
    inner.balances(innerBalances) ++ snapshotBalances
  }

  override def dccBalances(addresses: Seq[Address]): Map[Address, Long] = {
    val (innerBalances, snapshotBalances) = addresses
      .foldLeft((Seq[Address](), Map[Address, Long]())) { case ((innerBalances, snapshotBalances), address) =>
        snapshot.balances
          .get((address, Dcc))
          .fold(
            (innerBalances :+ address, snapshotBalances)
          )(balance => (innerBalances, snapshotBalances + (address -> balance)))
      }
    inner.dccBalances(innerBalances) ++ snapshotBalances
  }

  override def effectiveBalanceBanHeights(address: Address): Seq[Int] = {
    val maybeLastBlockBan = blockMeta.flatMap(_._1.header.challengedHeader).map(_.generator.toAddress) match {
      case Some(generator) if address == generator => Seq(height)
      case _                                       => Seq.empty
    }
    maybeLastBlockBan ++ inner.effectiveBalanceBanHeights(address)
  }

  override def leaseBalance(address: Address): LeaseBalance =
    snapshot.leaseBalances.getOrElse(address, inner.leaseBalance(address))

  override def leaseBalances(addresses: Seq[Address]): Map[Address, LeaseBalance] = {
    val (innerBalances, snapshotBalances) = addresses
      .foldLeft((Seq[Address](), Map[Address, LeaseBalance]())) { case ((innerBalances, snapshotBalances), address) =>
        snapshot.leaseBalances
          .get(address)
          .fold(
            (innerBalances :+ address, snapshotBalances)
          )(balance => (innerBalances, snapshotBalances + (address -> balance)))
      }
    inner.leaseBalances(innerBalances) ++ snapshotBalances
  }

  override def assetScript(asset: IssuedAsset): Option[AssetScriptInfo] =
    maybeSnapshot
      .flatMap(_.assetScripts.get(asset))
      .orElse(inner.assetScript(asset))

  override def assetDescription(asset: IssuedAsset): Option[AssetDescription] =
    SnapshotBlockchain.assetDescription(asset, snapshot, height, inner)

  override def leaseDetails(leaseId: ByteStr): Option[LeaseDetails] = {
    val newer = snapshot.newLeases.get(leaseId).map(n => LeaseDetails(n, LeaseDetails.Status.Active)).orElse(inner.leaseDetails(leaseId))
    snapshot.cancelledLeases.get(leaseId) match {
      case Some(newStatus) => newer.map(_.copy(status = newStatus))
      case None            => newer
    }
  }

  override def transferById(id: ByteStr): Option[(Int, TransferTransactionLike)] =
    snapshot.transactions
      .get(id)
      .collect { case NewTransactionInfo(tx: TransferTransaction, _, _, TxMeta.Status.Succeeded, _) =>
        (height, tx)
      }
      .orElse(inner.transferById(id))

  override def transactionInfo(id: ByteStr): Option[(TxMeta, Transaction)] =
    snapshot.transactions
      .get(id)
      .map(t => (TxMeta(Height(this.height), t.status, t.spentComplexity), t.transaction))
      .orElse(inner.transactionInfo(id))

  override def transactionInfos(ids: Seq[ByteStr]): Seq[Option[(TxMeta, Transaction)]] = {
    inner.transactionInfos(ids).zip(ids).map { case (info, id) =>
      snapshot.transactions
        .get(id)
        .map(t => (TxMeta(Height(this.height), t.status, t.spentComplexity), t.transaction))
        .orElse(info)
    }
  }

  override def transactionMeta(id: ByteStr): Option[TxMeta] =
    snapshot.transactions
      .get(id)
      .map(t => TxMeta(Height(this.height), t.status, t.spentComplexity))
      .orElse(inner.transactionMeta(id))

  override def transactionSnapshot(id: ByteStr): Option[(StateSnapshot, Status)] =
    snapshot.transactions
      .get(id)
      .map(tx => (tx.snapshot, tx.status))
      .orElse(inner.transactionSnapshot(id))

  override def height: Int = inner.height + blockMeta.size

  override def finalizedHeight: Option[Height] = inner.finalizedHeight

  override def finalizedHeightAt(at: Height): Option[Height] = inner.finalizedHeightAt(at)

  override def resolveAlias(alias: Alias): Either[ValidationError, Address] = inner.resolveAlias(alias) match {
    case l @ Left(AliasIsDisabled(_)) => l
    case Right(addr)                  => Right(snapshot.aliases.getOrElse(alias, addr))
    case Left(_)                      => snapshot.aliases.get(alias).toRight(AliasDoesNotExist(alias))
  }

  override def containsTransaction(tx: Transaction): Boolean =
    snapshot.transactions.contains(tx.id()) || inner.containsTransaction(tx)

  override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee =
    snapshot.orderFills.getOrElse(orderId, inner.filledVolumeAndFee(orderId))

  override def balanceAtHeight(address: Address, h: Int, assetId: Asset = Dcc): Option[(Int, Long)] =
    if (maybeSnapshot.forall(!_.balances.contains(address -> assetId)) || h < this.height) {
      inner.balanceAtHeight(address, h, assetId)
    } else {
      val balance = this.balance(address, assetId)
      val bs      = height -> balance
      Some(bs)
    }

  override def balanceSnapshots(address: Address, from: Int, to: Option[BlockId]): Seq[BalanceSnapshot] = {
    val from1 = math.max(from, 1)

    if (maybeSnapshot.isEmpty || to.exists(id => inner.heightOf(id).isDefined)) {
      inner.balanceSnapshots(address, from1, to)
    } else {
      val h       = Height(height)
      val balance = this.balance(address)
      val lease   = this.leaseBalance(address)
      val deposit = this.generationDeposit(address, h)

      val bs         = BalanceSnapshot(h, Portfolio(balance, lease, generationDeposit = deposit))
      val height2Fix = h.toInt == 2 && from1 < 2 && inner.isFeatureActivated(RideV6)
      if (inner.height > 0 && (from1 < h.toInt - 1 || height2Fix))
        bs +: inner.balanceSnapshots(address, from1, to)
      else
        Seq(bs)
    }
  }

  override def accountScript(address: Address): Option[AccountScriptInfo] =
    snapshot.accountScriptsByAddress.get(address) match {
      case None            => inner.accountScript(address)
      case Some(None)      => None
      case Some(Some(scr)) => Some(scr)
    }

  override def hasAccountScript(address: Address): Boolean =
    snapshot.accountScriptsByAddress.get(address) match {
      case None          => inner.hasAccountScript(address)
      case Some(None)    => false
      case Some(Some(_)) => true
    }

  override def accountData(acc: Address, key: String): Option[DataEntry[?]] =
    (for {
      d <- snapshot.accountData.get(acc)
      e <- d.get(key)
    } yield e).orElse(inner.accountData(acc, key)).filterNot(_.isEmpty)

  override def hasData(acc: Address): Boolean = {
    snapshot.accountData.contains(acc) || inner.hasData(acc)
  }

  override def carryFee(refId: Option[ByteStr]): Long = carry

  override def score: BigInt = blockMeta.fold(BigInt(0))(_._1.header.score()) + inner.score

  override def blockHeader(height: Int): Option[SignedBlockHeader] =
    blockMeta match {
      case Some((header, _)) if this.height == height => Some(header)
      case _                                          => inner.blockHeader(height)
    }

  override def heightOf(blockId: ByteStr): Option[Int] = blockMeta.filter(_._1.id() == blockId).map(_ => height) orElse inner.heightOf(blockId)

  /** Features related */
  override def approvedFeatures: Map[Short, Height] = inner.approvedFeatures

  override def activatedFeatures: Map[Short, Height] = inner.activatedFeatures

  override def featureVotes(height: Height): Map[Short, Int] = inner.featureVotes(height)

  /** Block reward related */
  override def blockReward(height: Int): Option[Long] = reward.filter(_ => this.height == height) orElse inner.blockReward(height)

  override def blockRewardVotes(height: Int): Seq[Long] = inner.blockRewardVotes(height)

  override def dccAmount(height: Int): BigInt = {
    val parentBlockHeader = blockMeta match {
      case None => inner.blockHeader(height - 1)
      case _    => inner.lastBlockHeader
    }

    val parentConflictEndorsements = for {
      parentBlockHeader <- parentBlockHeader
      voting            <- parentBlockHeader.header.finalizationVoting
    } yield voting.conflict.size

    inner.dccAmount(height) +
      BigInt(reward.getOrElse(0L)) -
      parentConflictEndorsements.getOrElse(0) * CommitToGenerationTransaction.DepositInDcclets
  }

  override def hitSource(height: Int): Option[ByteStr] =
    blockMeta
      .collect { case (_, hitSource) if this.height == height => hitSource }
      .orElse(inner.hitSource(height))

  override def resolveERC20Address(address: ERC20Address): Option[IssuedAsset] =
    inner
      .resolveERC20Address(address)
      .orElse(snapshot.erc20Addresses.get(address))

  override def lastStateHash(refId: Option[ByteStr]): BlockId =
    stateHash.orElse(blockMeta.flatMap(_._1.header.stateHash)).getOrElse(inner.lastStateHash(refId))

  override def committedGenerators(at: GenerationPeriod): IndexedSeq[(Address, BlsPublicKey)] = {
    val base   = inner.committedGenerators(at)
    val atNext = this.currentGenerationPeriod.exists(_.next == at)
    if (atNext) base ++ liveNextCommittedGenerators.map { case (pk, blsPk) => pk.toAddress -> blsPk } else base
  }

  /** `nextCommittedGenerators` entries actually backed by a non-elided `CommitToGenerationTransaction`
    * in this same snapshot.
    *
    * This is an ALLOWLIST, deliberately, not a filter that subtracts bad entries. `nextCommittedGenerators`
    * is a peer-supplied snapshot field that the fold merges unconditionally
    * ([[StateSnapshot.monoid]]), and it is a SEPARATE source from the transaction bodies that
    * `BlockDiffer`'s commitment guard validates. Keying on anything less than "a matching, non-elided
    * commitment transaction is present here" leaves two holes:
    *
    *   - an ELIDED commitment's entry (its effects are discarded by definition), and
    *   - a PHANTOM entry with no backing transaction at all -- a peer can attach one to any other
    *     transaction's snapshot, in a block containing no commitment, so the guard's work list is
    *     empty and it never runs. A subtract-the-elided filter cannot see this case, because there is
    *     no backing transaction of any status to subtract.
    *
    * Requiring positive backing closes both with one rule. The exposure is bounded to the liquid
    * window -- `Caches.append` persists from `txn.endorserPublicKey` on real transaction bodies, so a
    * phantom never reaches persisted state -- but this path feeds endorsement validation and the
    * `committedGeneratorsHash` computation while the block is liquid.
    */
  private def liveNextCommittedGenerators: Seq[(PublicKey, BlsPublicKey)] = {
    // An in-progress snapshot built by a transaction differ carries `nextCommittedGenerators` before
    // any transaction has been attached to it (see `CommitToGenerationTransactionDiff`, which then
    // reads `generatingBalance` back through this class to size the deposit). Such a snapshot has no
    // transactions at all, so there is nothing to reconcile against and nothing peer-supplied to
    // distrust -- reconciliation applies only once the snapshot actually carries transactions.
    if (snapshot.transactions.isEmpty) snapshot.nextCommittedGenerators
    else {
      val backed: Set[(PublicKey, BlsPublicKey)] = snapshot.transactions.values
        .collect {
          case nti if nti.status != TxMeta.Status.Elided =>
            nti.transaction match {
              case tx: CommitToGenerationTransaction => Some(tx.sender -> tx.endorserPublicKey)
              case _                                 => None
            }
        }
        .flatten
        .toSet

      snapshot.nextCommittedGenerators.filter(backed.contains)
    }
  }

  override def conflictGenerators(at: GenerationPeriod): ConflictGenerators = {
    lazy val base = inner.conflictGenerators(at)
    this.currentGenerationPeriod.fold(ConflictGenerators.empty) { currPeriod =>
      if (at < currPeriod) base
      else if (at > currPeriod) ConflictGenerators.empty
      else {
        val extraConflictIndexes = for {
          (blockMeta, _) <- blockMeta.toSeq
          v              <- blockMeta.header.finalizationVoting.toSeq
          idx            <- v.conflict.map(_.endorserIndex) ++ v.hotstuffConflicts.map(p => GeneratorIndex(p.voterIndex))
        } yield idx

        base.appendAll(Height(height), extraConflictIndexes*)
      }
    }
  }
}

object SnapshotBlockchain {
  def apply(inner: Blockchain, ngState: NgState): SnapshotBlockchain =
    new SnapshotBlockchain(
      inner,
      Some(ngState.bestLiquidSnapshot),
      Some(SignedBlockHeader(ngState.bestLiquidBlock.header, ngState.bestLiquidBlock.signature) -> ngState.hitSource),
      ngState.carryFee,
      ngState.reward,
      Some(ngState.bestLiquidComputedStateHash),
      Some(ngState.finalizationState.generatorSet)
    )

  def apply(inner: Blockchain, reward: Option[Long]): SnapshotBlockchain =
    new SnapshotBlockchain(inner, carry = inner.carryFee(None), reward = reward)

  def apply(inner: Blockchain, snapshot: StateSnapshot): SnapshotBlockchain =
    new SnapshotBlockchain(inner, Some(snapshot))

  def apply(
      inner: Blockchain,
      snapshot: StateSnapshot,
      newBlock: Block,
      hitSource: ByteStr,
      carry: Long,
      reward: Option[Long],
      stateHash: Option[ByteStr]
  ): SnapshotBlockchain =
    new SnapshotBlockchain(inner, Some(snapshot), Some(SignedBlockHeader(newBlock.header, newBlock.signature) -> hitSource), carry, reward, stateHash)

  private def assetDescription(
      asset: IssuedAsset,
      snapshot: StateSnapshot,
      height: Int,
      inner: Blockchain
  ): Option[AssetDescription] = {
    lazy val volume      = snapshot.assetVolumes.get(asset)
    lazy val info        = snapshot.assetNamesAndDescriptions.get(asset)
    lazy val sponsorship = snapshot.sponsorships.get(asset).map(_.minFee)
    lazy val script      = snapshot.assetScripts.get(asset)
    snapshot.assetStatics
      .get(asset)
      .map { case (static, assetNum) =>
        AssetDescription(
          static.source,
          static.issuer,
          info.get.name,
          info.get.description,
          static.decimals,
          volume.get.isReissuable,
          volume.get.volume,
          info.get.lastUpdatedAt,
          script,
          sponsorship.getOrElse(0),
          static.nft,
          assetNum,
          Height(height)
        )
      }
      .orElse(
        inner
          .assetDescription(asset)
          .map(d =>
            d.copy(
              totalVolume = volume.map(_.volume).getOrElse(d.totalVolume),
              reissuable = volume.map(_.isReissuable).getOrElse(d.reissuable),
              name = info.map(_.name).getOrElse(d.name),
              description = info.map(_.description).getOrElse(d.description),
              lastUpdatedAt = info.map(_.lastUpdatedAt).getOrElse(d.lastUpdatedAt),
              sponsorship = sponsorship.getOrElse(d.sponsorship),
              script = script.orElse(d.script)
            )
          )
      )
  }
}
