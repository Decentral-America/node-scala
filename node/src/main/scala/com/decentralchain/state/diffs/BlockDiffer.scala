package com.decentralchain.state.diffs

import cats.implicits.{catsSyntaxOption, catsSyntaxSemigroup, toFoldableOps}
import cats.syntax.either.*
import com.decentralchain.account.Address
import com.decentralchain.crypto
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.{Block, BlockSnapshot, FinalizationVoting, MicroBlock, MicroBlockSnapshot}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.lang.ValidationError
import com.decentralchain.mining.MiningConstraint
import com.decentralchain.state.*
import com.decentralchain.state.StateSnapshot.monoid
import com.decentralchain.state.TxStateSnapshotHashBuilder.TxStatusInfo
import com.decentralchain.state.patch.*
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.TxValidationError.*
import com.decentralchain.transaction.assets.exchange.ExchangeTransaction
import com.decentralchain.transaction.lease.LeaseTransaction
import com.decentralchain.transaction.smart.InvokeScriptTransaction
import com.decentralchain.transaction.smart.script.trace.TracedResult
import com.decentralchain.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.decentralchain.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.decentralchain.transaction.{
  Asset,
  Authorized,
  BlockchainUpdater,
  CommitToGenerationTransaction,
  GenesisTransaction,
  PaymentTransaction,
  Transaction
}

import scala.collection.immutable.VectorMap

object BlockDiffer {
  final case class Result(
      snapshot: StateSnapshot,
      carry: Long,
      totalFee: Long,
      constraint: MiningConstraint,
      keyBlockSnapshot: StateSnapshot,
      computedStateHash: ByteStr
  )

  case class Fraction(dividend: Int, divider: Int) {
    def apply(l: Long): Long = l / divider * dividend
  }

  case class TxFeeInfo(feeAsset: Asset, feeAmount: Long, carry: Long, dccFee: Long)

  val CurrentBlockFeePart: Fraction = Fraction(2, 5)

  def fromBlock(
      blockchain: Blockchain,
      maybePrevBlock: Option[Block],
      block: Block,
      snapshot: Option[BlockSnapshot],
      constraint: MiningConstraint,
      hitSource: ByteStr,
      challengedHitSource: Option[ByteStr] = None,
      loadCacheData: (Set[Address], Set[ByteStr]) => Unit = (_, _) => (),
      verify: Boolean = true,
      enableExecutionLog: Boolean = false,
      txSignParCheck: Boolean = true
  ): Either[ValidationError, Result] = {
    challengedHitSource match {
      case Some(hs) if snapshot.isEmpty =>
        fromBlockTraced(
          blockchain,
          maybePrevBlock,
          block.toOriginal,
          snapshot,
          constraint,
          hs,
          loadCacheData,
          verify,
          enableExecutionLog,
          txSignParCheck
        ).resultE match {
          case Left(_: InvalidStateHash) =>
            fromBlockTraced(
              blockchain,
              maybePrevBlock,
              block,
              snapshot,
              constraint,
              hitSource,
              loadCacheData,
              verify,
              enableExecutionLog,
              txSignParCheck
            ).resultE
          case Left(err) => Left(GenericError(s"Invalid block challenge: $err"))
          case _         => Left(GenericError("Invalid block challenge"))
        }
      case _ =>
        fromBlockTraced(
          blockchain,
          maybePrevBlock,
          block,
          snapshot,
          constraint,
          hitSource,
          loadCacheData,
          verify,
          enableExecutionLog,
          txSignParCheck
        ).resultE
    }
  }

  def fromBlockTraced(
      blockchain: Blockchain,
      maybePrevBlock: Option[Block],
      block: Block,
      snapshot: Option[BlockSnapshot],
      constraint: MiningConstraint,
      hitSource: ByteStr,
      loadCacheData: (Set[Address], Set[ByteStr]) => Unit,
      verify: Boolean,
      enableExecutionLog: Boolean,
      txSignParCheck: Boolean
  ): TracedResult[ValidationError, Result] = {
    val stateHeight        = Height(blockchain.height)
    val heightWithNewBlock = stateHeight + 1

    // height switch is next after activation
    val ngHeight          = blockchain.featureActivationHeight(BlockchainFeatures.NG).getOrElse(Height(Int.MaxValue))
    val sponsorshipHeight = Sponsorship.sponsoredFeesSwitchHeight(blockchain)

    val feeFromPreviousBlockE =
      if (stateHeight >= sponsorshipHeight) {
        Right(Portfolio(balance = blockchain.carryFee(None)))
      } else if (stateHeight > ngHeight) maybePrevBlock.fold(Portfolio.empty.asRight[String]) { pb =>
        // it's important to combine tx fee fractions (instead of getting a fraction of the combined tx fee)
        // so that we end up with the same value as when computing per-transaction fee part
        // during microblock processing below
        //
        // CommitToGenerationTransaction fees are excluded from the NG carry-over for the same
        // reason their commitment data is excluded from the state hash (see
        // TxStateSnapshotHashBuilder.scala): letting the 60% carry-share participate in the
        // normal split would make the amount carried into the NEXT block depend on which
        // block position the commitment tx landed at -- the same position-dependent-state
        // hazard Feature 21 guards against for the state hash itself.
        //
        // Precise effect (confirmed against canonical): the committing block's OWN miner still
        // gets the normal 40% immediate cut (unchanged, via minerPortfolio in apply() below --
        // this filter does not touch that); this exclusion only zeroes the OTHER 60% that would
        // otherwise carry to the next block. Net result for a CommitToGenerationTransaction fee
        // is 40% to the block that includes it, 0% to the next block -- the remaining 60% is not
        // credited to anyone (removed from circulation for accounting purposes, though the fee
        // itself was already debited from the sender by CommitToGenerationTransactionDiff, so no
        // extra amount is actually burned beyond the fee itself). Verified against the real
        // testnet chain's canonical history at height 1799 (the block immediately after the
        // chain's first CommitToGenerationTransaction commitments) and confirmed by a fresh-
        // genesis replay matching canonical stateHash through height ~3300.
        pb.transactionData
          .filterNot(_.isInstanceOf[CommitToGenerationTransaction])
          .map { t =>
            val pf = Portfolio.build(t.assetFee)
            pf.minus(pf.multiply(CurrentBlockFeePart))
          }
          .foldM(Portfolio.empty)(_.combine(_))
      }
      else
        Right(Portfolio.empty)

    val initialFeeFromThisBlockE =
      if (stateHeight < ngHeight) {
        // before NG activation, miner immediately received all the fee from the block
        block.transactionData.map(_.assetFee).map(Portfolio.build).foldM(Portfolio.empty)(_.combine(_))
      } else
        Right(Portfolio.empty)

    val addressRewardsE: Either[String, (Portfolio, Map[Address, Portfolio], Map[Address, Portfolio])] = for {
      daoAddress        <- blockchain.settings.functionalitySettings.daoAddressParsed
      xtnBuybackAddress <- blockchain.settings.functionalitySettings.xtnBuybackAddressParsed
    } yield {
      val blockRewardShares = BlockRewardCalculator.getBlockRewardShares(
        heightWithNewBlock,
        blockchain.lastBlockReward.getOrElse(0L),
        daoAddress,
        xtnBuybackAddress,
        blockchain
      )
      (
        Portfolio.dcc(blockRewardShares.miner),
        daoAddress.fold(Map[Address, Portfolio]())(addr => Map(addr -> Portfolio.dcc(blockRewardShares.daoAddress)).filter(_._2.balance > 0)),
        xtnBuybackAddress.fold(Map[Address, Portfolio]())(addr =>
          Map(addr -> Portfolio.dcc(blockRewardShares.xtnBuybackAddress)).filter(_._2.balance > 0)
        )
      )
    }

    val blockchainWithNewBlock = SnapshotBlockchain(blockchain, StateSnapshot.empty, block, hitSource, 0, blockchain.lastBlockReward, None)
    val initSnapshotE          =
      for {
        feeFromPreviousBlock                             <- feeFromPreviousBlockE
        initialFeeFromThisBlock                          <- initialFeeFromThisBlockE
        totalFee                                         <- initialFeeFromThisBlock.combine(feeFromPreviousBlock)
        (minerReward, daoPortfolio, xtnBuybackPortfolio) <- addressRewardsE
        totalMinerReward                                 <- minerReward.combine(totalFee)
        totalMinerPortfolio = Map(block.sender.toAddress -> totalMinerReward)
        nonMinerRewardPortfolios <- Portfolio.combine(daoPortfolio, xtnBuybackPortfolio)
        totalRewardPortfolios    <- Portfolio.combine(totalMinerPortfolio, nonMinerRewardPortfolios)
        penalties                <- maybePrevBlock match {
          case Some(prevBlock) => calculatePenalties(blockchain, prevBlock)
          case None            => Map.empty[Address, Portfolio].asRight[String]
        }
        withPenaltiesPortfolios <- Portfolio.combine(penalties, totalRewardPortfolios)
        patchesSnapshot = leasePatchesSnapshot(blockchainWithNewBlock)
        resultSnapshot <- patchesSnapshot.addBalances(withPenaltiesPortfolios, blockchainWithNewBlock)
      } yield resultSnapshot

    for {
      _            <- TracedResult(Either.cond(!verify || block.signatureValid(), (), GenericError(s"Block $block has invalid signature")))
      initSnapshot <- TracedResult(initSnapshotE.leftMap(GenericError(_)))
      prevStateHash = maybePrevBlock.flatMap(_.header.stateHash).getOrElse(blockchain.lastStateHash(None))
      hasChallenge  = block.header.challengedHeader.isDefined
      r <- snapshot match {
        case Some(BlockSnapshot(_, txSnapshots)) =>
          TracedResult.wrapValue(
            apply(blockchainWithNewBlock, prevStateHash, initSnapshot, stateHeight >= ngHeight, block.transactionData, txSnapshots)
          )
        case None =>
          apply(
            blockchainWithNewBlock,
            constraint,
            maybePrevBlock.map(_.header.timestamp),
            prevStateHash,
            initSnapshot,
            stateHeight >= ngHeight,
            hasChallenge,
            block.transactionData,
            loadCacheData,
            verify = verify,
            enableExecutionLog = enableExecutionLog,
            txSignParCheck = txSignParCheck
          )
      }
      _ <- checkStateHash(blockchainWithNewBlock, block.header.stateHash, r.computedStateHash)
      _ <- checkCommittedGeneratorsHash(blockchain, heightWithNewBlock, block.header.committedGeneratorsHash)
    } yield r
  }

  // Blocks mined before the committedGeneratorsHash feature existed have this field unset
  // (None) at period boundaries. Rejecting None as a mismatch would break sync for every
  // historic boundary block, so validation is deliberately asymmetric:
  //   - None      => always accepted, UNCONDITIONALLY, forever (see caveat below)
  //   - Some(h)   => strictly validated: boundary blocks must carry the correct hash,
  //                  non-boundary blocks must not carry one at all.
  //
  // IMPORTANT CAVEAT (confirmed by adversarial review, not yet closed): because None is
  // accepted at every height with no feature-activation cutover, this check currently
  // provides NO real enforcement -- any producer (outdated, buggy, or malicious) can omit
  // the field at a real boundary and every other node accepts the block regardless.
  // Committee integrity at boundaries is therefore NOT yet actually guaranteed by this
  // mechanism on its own. This is a known, pre-existing gap in the original (recovered)
  // design, already flagged as deferred hardening ("make committedGeneratorsHash mandatory
  // at period-boundary heights once all producers are upgraded" -- not a regression
  // introduced here. Closing it requires a coordinated activation-height rollout (a None at
  // a boundary should become a hard rejection once all producers are confirmed upgraded),
  // which is a rollout/governance decision, not purely a code change -- tracked as a
  // follow-up, intentionally not implemented in this change.
  private def checkCommittedGeneratorsHash(
      blockchain: Blockchain,
      height: Height,
      actual: Option[ByteStr]
  ): TracedResult[ValidationError, Unit] =
    actual match {
      case None             => TracedResult(Right(()))
      case Some(actualHash) =>
        // A period boundary is the LAST height of a GenerationPeriod (period.end), not simply
        // "height % generationPeriodLength == 0" -- see the matching comment in Miner.scala for
        // why the naive modulo check is wrong on any chain where DeterministicFinality activates
        // at a non-zero, non-period-aligned height.
        val expected =
          blockchain.generationPeriodOf(height).filter(_.end == height).map { period =>
            val validators = blockchain.committedGenerators(period.next).sortBy(_._1.toString)
            ByteStr(crypto.fastHash(validators.flatMap { case (addr, blsKey) => addr.bytes ++ blsKey.arr }.toArray))
          }
        TracedResult(
          Either.cond(
            expected.contains(actualHash),
            (),
            GenericError(s"committedGeneratorsHash mismatch at height $height: expected $expected, got $actual")
          )
        )
    }

  def fromMicroBlock(
      blockchain: Blockchain,
      prevBlockTimestamp: Option[Long],
      prevStateHash: ByteStr,
      micro: MicroBlock,
      snapshot: Option[MicroBlockSnapshot],
      constraint: MiningConstraint,
      loadCacheData: (Set[Address], Set[ByteStr]) => Unit = (_, _) => (),
      verify: Boolean = true,
      enableExecutionLog: Boolean = false
  ): Either[ValidationError, Result] =
    fromMicroBlockTraced(
      blockchain,
      prevBlockTimestamp,
      prevStateHash,
      micro,
      snapshot,
      constraint,
      loadCacheData,
      verify,
      enableExecutionLog
    ).resultE

  private def fromMicroBlockTraced(
      blockchain: Blockchain,
      prevBlockTimestamp: Option[Long],
      prevStateHash: ByteStr,
      micro: MicroBlock,
      snapshot: Option[MicroBlockSnapshot],
      constraint: MiningConstraint,
      loadCacheData: (Set[Address], Set[ByteStr]) => Unit,
      verify: Boolean,
      enableExecutionLog: Boolean
  ): TracedResult[ValidationError, Result] = {
    for {
      // microblocks are processed within block which is next after 40-only-block which goes on top of activated height
      _ <- TracedResult(
        Either.cond(
          blockchain.activatedFeatures.contains(BlockchainFeatures.NG.id),
          (),
          ActivationError(s"MicroBlocks are not yet activated")
        )
      )
      _ <- TracedResult(micro.signaturesValid())
      r <- snapshot match {
        case Some(MicroBlockSnapshot(_, txSnapshots)) =>
          TracedResult.wrapValue(apply(blockchain, prevStateHash, StateSnapshot.empty, hasNg = true, micro.transactionData, txSnapshots))
        case None =>
          apply(
            blockchain,
            constraint,
            prevBlockTimestamp,
            prevStateHash,
            StateSnapshot.empty,
            hasNg = true,
            hasChallenge = false,
            micro.transactionData,
            loadCacheData,
            verify = verify,
            enableExecutionLog = enableExecutionLog,
            txSignParCheck = true
          )
      }
      _ <- checkStateHash(blockchain, micro.stateHash, r.computedStateHash)
    } yield r
  }

  private def calculatePenalties(blockchain: Blockchain, prevBlockId: BlockId): Either[String, Map[Address, Portfolio]] = {
    val empty           = Map.empty[Address, Portfolio].asRight[String]
    val parentBlockInfo = for {
      prevHeight <- blockchain.heightOf(prevBlockId)
      period     <- blockchain.generationPeriodOf(Height(prevHeight))
      voting     <- blockchain.blockHeader(prevHeight).flatMap(_.header.finalizationVoting)
    } yield (period, voting)

    parentBlockInfo.fold(empty) { case (period, voting) =>
      calculatePenalties(blockchain, period, voting)
    }
  }

  private def calculatePenalties(blockchain: Blockchain, prevBlock: Block): Either[String, Map[Address, Portfolio]] = {
    val empty           = Map.empty[Address, Portfolio].asRight[String]
    val parentBlockInfo = for {
      voting     <- prevBlock.header.finalizationVoting
      prevHeight <- blockchain.heightOf(prevBlock.id())
      period     <- blockchain.generationPeriodOf(Height(prevHeight))
    } yield (period, voting)

    parentBlockInfo.fold(empty) { case (period, voting) =>
      calculatePenalties(blockchain, period, voting)
    }
  }

  private def calculatePenalties(
      blockchain: Blockchain,
      prevBlockPeriod: GenerationPeriod,
      prevBlockVoting: FinalizationVoting
  ): Either[String, Map[Address, Portfolio]] = {
    val empty          = Map.empty[Address, Portfolio].asRight[String]
    lazy val committed = blockchain.committedGenerators(prevBlockPeriod)
    prevBlockVoting.conflict.foldLeft(empty) {
      case (r @ Left(_), _)        => r
      case (Right(r), endorsement) =>
        committed.lift(endorsement.endorserIndex.toInt) match {
          case None            => Left(s"Invalid endorsement index in $endorsement, valid: [0; ${committed.size}]")
          case Some((addr, _)) =>
            val orig    = r.getOrElse(addr, Portfolio.empty)
            val updated = orig.combine(Portfolio.dcc(-CommitToGenerationTransaction.DepositInDcclets))
            updated.map(r.updated(addr, _))
        }
    }
  }

  def maybeApplySponsorship(blockchain: Blockchain, sponsorshipEnabled: Boolean, transactionFee: (Asset, Long)): (Asset, Long) =
    transactionFee match {
      case (ia: IssuedAsset, fee) if sponsorshipEnabled =>
        Dcc -> Sponsorship.toDcc(fee, blockchain.assetDescription(ia).get.sponsorship)
      case _ => transactionFee
    }

  def createInitialBlockSnapshot(
      blockchainUpdater: BlockchainUpdater & Blockchain,
      reference: ByteStr,
      miner: Address
  ): Either[ValidationError, StateSnapshot] = {
    val blockchain           = blockchainUpdater.referencedBlockchain(reference)
    val feeFromPreviousBlock = Portfolio.dcc(blockchain.carryFee(Some(reference)))

    val daoAddress        = blockchain.settings.functionalitySettings.daoAddressParsed.toOption.flatten
    val xtnBuybackAddress = blockchain.settings.functionalitySettings.xtnBuybackAddressParsed.toOption.flatten

    val rewardShares = BlockRewardCalculator.getBlockRewardShares(
      Height(blockchain.height + 1),
      blockchainUpdater.computeNextReward.getOrElse(0),
      daoAddress,
      xtnBuybackAddress,
      blockchain
    )

    for {
      minerReward <- Portfolio.dcc(rewardShares.miner).combine(feeFromPreviousBlock).leftMap(GenericError(_))
      resultPf = Map(miner -> minerReward) ++
        daoAddress.map(_ -> Portfolio.dcc(rewardShares.daoAddress)) ++
        xtnBuybackAddress.map(_ -> Portfolio.dcc(rewardShares.xtnBuybackAddress))
      withRewards   <- StateSnapshot.build(blockchain, portfolios = resultPf.filterNot(_._2.isEmpty))
      penaltiesPf   <- calculatePenalties(blockchain, reference).leftMap(GenericError(_))
      withPenalties <- withRewards.addBalances(penaltiesPf, blockchain).leftMap(GenericError(_))
    } yield withPenalties
  }

  def computeInitialStateHash(blockchain: Blockchain, initSnapshot: StateSnapshot, prevStateHash: ByteStr): ByteStr = {
    if (initSnapshot == StateSnapshot.empty || blockchain.height == 1)
      prevStateHash
    else
      TxStateSnapshotHashBuilder.createHashFromSnapshot(initSnapshot, None).createHash(prevStateHash)
  }

  private def apply(
      blockchain: Blockchain,
      initConstraint: MiningConstraint,
      prevBlockTimestamp: Option[Long],
      prevStateHash: ByteStr,
      initSnapshot: StateSnapshot,
      hasNg: Boolean,
      hasChallenge: Boolean,
      txs: Seq[Transaction],
      loadCacheData: (Set[Address], Set[ByteStr]) => Unit,
      verify: Boolean,
      enableExecutionLog: Boolean,
      txSignParCheck: Boolean
  ): TracedResult[ValidationError, Result] = {
    val timestamp       = blockchain.lastBlockTimestamp.get
    val blockGenerator  = blockchain.lastBlockHeader.get.header.generator.toAddress
    val rideV6Activated = blockchain.isFeatureActivated(BlockchainFeatures.RideV6)

    val txDiffer = TransactionDiffer(prevBlockTimestamp, timestamp, verify, enableExecutionLog = enableExecutionLog)

    if (verify && txSignParCheck)
      ParSignatureChecker.checkTxSignatures(txs, rideV6Activated)

    prepareCaches(blockGenerator, txs, loadCacheData)

    val initStateHash = computeInitialStateHash(blockchain, initSnapshot, prevStateHash)
    txs
      .foldLeft(TracedResult(Result(initSnapshot, 0L, 0L, initConstraint, initSnapshot, initStateHash).asRight[ValidationError])) {
        case (acc @ TracedResult(Left(_), _, _), _) => acc
        case (
              TracedResult(
                Right(
                  result @ Result(currSnapshot, carryFee, currTotalFee, currConstraint, keyBlockSnapshot, prevStateHash)
                ),
                _,
                _
              ),
              tx
            ) =>
          val currBlockchain = SnapshotBlockchain(blockchain, currSnapshot)
          val res            = txDiffer(currBlockchain, tx).flatMap { txSnapshot =>
            val updatedConstraint = currConstraint.put(currBlockchain, tx, txSnapshot)
            if (updatedConstraint.isOverfilled)
              TracedResult(Left(GenericError(s"Limit of txs was reached: $initConstraint -> $updatedConstraint")))
            else {
              val txFeeInfo = computeTxFeeInfo(currBlockchain, tx, hasNg)

              // unless NG is activated, miner has already received all the fee from this block by the time the first
              // transaction is processed (see abode), so there's no need to include tx fee into portfolio.
              // if NG is activated, just give them their 40%
              val minerPortfolio =
                if (!hasNg) Portfolio.empty else Portfolio.build(txFeeInfo.feeAsset, txFeeInfo.feeAmount).multiply(CurrentBlockFeePart)
              val minerPortfolioMap = Map(blockGenerator -> minerPortfolio)

              txSnapshot.addBalances(minerPortfolioMap, currBlockchain).leftMap(GenericError(_)).map { resultTxSnapshot =>
                val (_, txInfo)         = txSnapshot.transactions.head
                val txInfoWithFee       = txInfo.copy(snapshot = resultTxSnapshot.copy(transactions = VectorMap.empty))
                val newKeyBlockSnapshot = keyBlockSnapshot.withTransaction(txInfoWithFee)

                val newSnapshot = currSnapshot |+| resultTxSnapshot.withTransaction(txInfoWithFee)

                Result(
                  newSnapshot,
                  carryFee + txFeeInfo.carry,
                  currTotalFee + txFeeInfo.dccFee,
                  updatedConstraint,
                  newKeyBlockSnapshot,
                  TxStateSnapshotHashBuilder
                    .createHashFromSnapshot(resultTxSnapshot, Some(TxStatusInfo(txInfo.transaction.id(), txInfo.status)))
                    .createHash(prevStateHash)
                )
              }
            }
          }

          res.copy(resultE = res.resultE.recover {
            case _ if hasChallenge =>
              result.copy(
                snapshot = result.snapshot.bindElidedTransaction(currBlockchain, tx),
                computedStateHash = TxStateSnapshotHashBuilder
                  .createHashFromSnapshot(StateSnapshot.empty, Some(TxStatusInfo(tx.id(), TxMeta.Status.Elided)))
                  .createHash(result.computedStateHash)
              )
          })
      }
  }

  private def apply(
      blockchain: Blockchain,
      prevStateHash: ByteStr,
      initSnapshot: StateSnapshot,
      hasNg: Boolean,
      txs: Seq[Transaction],
      txSnapshots: Seq[(StateSnapshot, TxMeta.Status)]
  ): Result = {
    val initStateHash = computeInitialStateHash(blockchain, initSnapshot, prevStateHash)
    txs.zip(txSnapshots).foldLeft(Result(initSnapshot, 0L, 0L, MiningConstraint.Unlimited, initSnapshot, initStateHash)) {
      case (Result(currSnapshot, carryFee, currTotalFee, currConstraint, keyBlockSnapshot, prevStateHash), (tx, (txSnapshot, txStatus))) =>
        val currBlockchain = SnapshotBlockchain(blockchain, currSnapshot)

        val txFeeInfo = if (txStatus == TxMeta.Status.Elided) None else Some(computeTxFeeInfo(currBlockchain, tx, hasNg))
        val nti       = NewTransactionInfo.create(tx, txStatus, txSnapshot, currBlockchain)

        Result(
          currSnapshot |+| txSnapshot.withTransaction(nti),
          carryFee + txFeeInfo.map(_.carry).getOrElse(0L),
          currTotalFee + txFeeInfo.map(_.dccFee).getOrElse(0L),
          currConstraint,
          keyBlockSnapshot.withTransaction(nti),
          TxStateSnapshotHashBuilder.createHashFromSnapshot(txSnapshot, Some(TxStatusInfo(tx.id(), txStatus))).createHash(prevStateHash)
        )
    }
  }

  private def computeTxFeeInfo(blockchain: Blockchain, tx: Transaction, hasNg: Boolean): TxFeeInfo = {
    val hasSponsorship        = Height(blockchain.height) >= Sponsorship.sponsoredFeesSwitchHeight(blockchain)
    val (feeAsset, feeAmount) = maybeApplySponsorship(blockchain, hasSponsorship, tx.assetFee)
    val currentBlockFee       = CurrentBlockFeePart(feeAmount)

    // carry is 60% of dcc fees the next miner will get. obviously carry fee only makes sense when both
    // NG and sponsorship is active. also if sponsorship is active, feeAsset can only be Dcc
    //
    // CommitToGenerationTransaction is excluded from carrying its 60% share forward regardless
    // of era, for the same position-dependent-state reason as the pre-sponsorship recompute
    // path in feeFromPreviousBlockE above -- see the detailed comment there (the committing
    // block's own miner still gets the normal 40% cut via minerPortfolio; only the 60% that
    // would otherwise carry to the next block is zeroed here).
    val carry  = if (hasNg && hasSponsorship && !tx.isInstanceOf[CommitToGenerationTransaction]) feeAmount - currentBlockFee else 0
    val dccFee = if (feeAsset == Dcc) feeAmount else 0L

    TxFeeInfo(feeAsset, feeAmount, carry, dccFee)
  }

  private def leasePatchesSnapshot(blockchain: Blockchain): StateSnapshot =
    Seq(CancelAllLeases, CancelLeaseOverflow, CancelInvalidLeaseIn, CancelLeasesToDisabledAliases)
      .foldLeft(StateSnapshot.empty) { case (prevSnapshot, patch) =>
        prevSnapshot |+| patch.lift(SnapshotBlockchain(blockchain, prevSnapshot)).orEmpty
      }

  private def prepareCaches(blockGenerator: Address, txs: Seq[Transaction], loadCacheData: (Set[Address], Set[ByteStr]) => Unit): Unit = {
    val addresses = Set.newBuilder[Address].addOne(blockGenerator)
    val orders    = Set.newBuilder[ByteStr]

    txs.foreach {
      case tx: ExchangeTransaction =>
        addresses.addAll(Seq(tx.sender.toAddress, tx.buyOrder.senderAddress, tx.sellOrder.senderAddress))
        orders.addOne(tx.buyOrder.id()).addOne(tx.sellOrder.id())
      case tx: GenesisTransaction      => addresses.addOne(tx.recipient)
      case tx: InvokeScriptTransaction =>
        addresses.addAll(Seq(tx.senderAddress) ++ (tx.dApp match {
          case addr: Address => Some(addr)
          case _             => None
        }))
      case tx: LeaseTransaction =>
        addresses.addAll(Seq(tx.sender.toAddress) ++ (tx.recipient match {
          case addr: Address => Some(addr)
          case _             => None
        }))
      case tx: MassTransferTransaction =>
        addresses.addAll(Seq(tx.sender.toAddress) ++ tx.transfers.collect { case ParsedTransfer(addr: Address, _) => addr })
      case tx: PaymentTransaction  => addresses.addAll(Seq(tx.sender.toAddress, tx.recipient))
      case tx: TransferTransaction =>
        addresses.addAll(Seq(tx.sender.toAddress) ++ (tx.recipient match {
          case addr: Address => Some(addr)
          case _             => None
        }))
      case tx: Authorized => addresses.addOne(tx.sender.toAddress)
      case _              => ()
    }

    loadCacheData(addresses.result(), orders.result())
  }

  private def checkStateHash(
      blockchain: Blockchain,
      blockStateHash: Option[ByteStr],
      computedStateHash: ByteStr
  ): TracedResult[ValidationError, Unit] =
    Either.cond(
      !blockchain.supportsLightNodeBlockFields() || blockStateHash.contains(computedStateHash),
      (),
      InvalidStateHash(blockStateHash, Some(computedStateHash))
    )
}
