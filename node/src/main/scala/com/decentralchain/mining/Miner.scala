package com.decentralchain.mining

import cats.syntax.either.*
import com.decentralchain.account.{Address, KeyPair, PKKeyPair}
import com.decentralchain.block.Block.*
import com.decentralchain.block.{Block, FinalizationVoting, SignedBlockHeader}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.HotStuffEquivocationProof
import com.decentralchain.consensus.nxt.NxtLikeConsensusBlockData
import com.decentralchain.consensus.{GeneratingBalanceProvider, PoSSelector}
import com.decentralchain.crypto
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.metrics.{BlockStats, Instrumented, *}
import com.decentralchain.mining.Miner.*
import com.decentralchain.mining.microblocks.MicroBlockMiner
import com.decentralchain.network.*
import com.decentralchain.settings.DCCSettings
import com.decentralchain.state.*
import com.decentralchain.state.BlockchainUpdaterImpl.BlockApplyResult.{Applied, Ignored}
import com.decentralchain.state.appender.BlockAppender
import com.decentralchain.state.diffs.BlockDiffer
import com.decentralchain.transaction.*
import com.decentralchain.transaction.TxValidationError.BlockFromFuture
import com.decentralchain.utils.{ScorexLogging, Time}
import com.decentralchain.utx.UtxPool
import com.decentralchain.utx.UtxPool.PackStrategy
import com.decentralchain.wallet.Wallet
import io.netty.channel.group.ChannelGroup
import kamon.Kamon
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.cancelables.{CompositeCancelable, SerialCancelable}
import monix.reactive.Observable

import java.time.LocalTime
import scala.concurrent.duration.*

trait Miner {
  def scheduleMining(baseBlockchain: Option[Blockchain] = None, cancelMicroBlockMining: Boolean = true): Unit
}

trait MinerDebugInfo {
  def state: MinerDebugInfo.State
  def getNextBlockGenerationOffset(account: KeyPair): Either[String, FiniteDuration]
}

object MinerDebugInfo {
  sealed trait State
  case object MiningBlocks              extends State
  case object MiningMicroblocks         extends State
  case object Disabled                  extends State
  final case class Error(error: String) extends State
}

class MinerImpl(
    allChannels: ChannelGroup,
    blockchainUpdater: Blockchain & BlockchainUpdater & NG,
    settings: DCCSettings,
    timeService: Time,
    utx: UtxPool,
    blockEndorser: BlockEndorser,
    endorsementStorage: EndorsementStorage,
    wallet: Wallet,
    pos: PoSSelector,
    val minerScheduler: Scheduler,
    val appenderScheduler: Scheduler,
    transactionAdded: Observable[Unit],
    maxTimeDrift: Long = appender.MaxTimeDrift,
    hotStuffEquivocations: () => Seq[HotStuffEquivocationProof] = () => Seq.empty
) extends Miner
    with MinerDebugInfo
    with ScorexLogging {

  private val minerSettings              = settings.minerSettings
  private val minMicroBlockDurationMills = minerSettings.minMicroBlockAge.toMillis
  private val blockchainSettings         = settings.blockchainSettings

  private val scheduledAttempts = SerialCancelable()
  private val microBlockAttempt = SerialCancelable()

  @volatile
  private var debugStateRef: MinerDebugInfo.State = MinerDebugInfo.Disabled

  private val microBlockMiner: MicroBlockMiner = MicroBlockMiner(
    debugStateRef = _,
    allChannels,
    blockchainUpdater,
    utx,
    endorsementStorage,
    blockEndorser,
    settings.minerSettings,
    minerScheduler,
    appenderScheduler,
    transactionAdded
  )

  def getNextBlockGenerationOffset(account: KeyPair): Either[String, FiniteDuration] =
    this.nextBlockGenOffsetWithConditions(account, blockchainUpdater)

  def scheduleMining(baseBlockchain: Option[Blockchain], cancelMicroBlockMining: Boolean): Unit =
    if (!settings.enableLightMode || blockchainUpdater.supportsLightNodeBlockFields()) {
      val accounts = if (settings.minerSettings.privateKeys.nonEmpty) {
        settings.minerSettings.privateKeys.map(PKKeyPair(_))
      } else {
        wallet.privateKeyAccounts
      }

      scheduledAttempts := CompositeCancelable.fromSet(accounts.map { account =>
        generateBlockTask(account, baseBlockchain)
          .onErrorHandle(err => log.warn(s"Error mining block by ${account.toAddress}: ${err.getMessage}"))
          .runAsyncLogErr(using appenderScheduler)
      }.toSet)

      if (cancelMicroBlockMining) {
        Miner.blockMiningStarted.increment()
        microBlockAttempt := SerialCancelable()
        debugStateRef = MinerDebugInfo.MiningBlocks
      }
    }

  override def state: MinerDebugInfo.State = debugStateRef

  private def checkAge(parentHeight: Int, parentTimestamp: Long): Either[String, Unit] =
    Either
      .cond(parentHeight == 1, (), (timeService.correctedTime() - parentTimestamp).millis)
      .left
      .flatMap(blockAge =>
        Either.cond(
          blockAge <= minerSettings.intervalAfterLastBlockThenGenerationIsAllowed,
          (),
          s"BlockChain is too old (last block timestamp is $parentTimestamp generated $blockAge ago)"
        )
      )

  private def ngEnabled: Boolean = blockchainUpdater.featureActivationHeight(BlockchainFeatures.NG).exists(Height(blockchainUpdater.height) > _ + 1)

  private def consensusData(blockchain: Blockchain, account: KeyPair, blockTime: Long): Either[String, NxtLikeConsensusBlockData] = {
    val lastBlockHeader = blockchain.lastBlockHeader.get.header
    pos
      .copy(blockchain = blockchain)
      .consensusData(
        account,
        blockchain.height,
        blockchainSettings.genesisSettings.averageBlockDelay,
        lastBlockHeader.baseTarget,
        lastBlockHeader.timestamp,
        blockchainUpdater.parentHeader(lastBlockHeader, 2).map(_.timestamp),
        blockTime
      )
      .leftMap(_.toString)
  }

  private def packTransactionsForKeyBlock(
      miner: Address,
      reference: ByteStr,
      prevStateHash: Option[ByteStr]
  ): (Seq[Transaction], MiningConstraint, Option[ByteStr]) = {
    val estimators        = MiningConstraints(blockchainUpdater, blockchainUpdater.height, Some(minerSettings))
    val keyBlockStateHash = prevStateHash.flatMap { prevHash =>
      BlockDiffer
        .createInitialBlockSnapshot(blockchainUpdater, reference, miner)
        .toOption
        .map { initSnapshot =>
          if (initSnapshot == StateSnapshot.empty) prevHash
          else TxStateSnapshotHashBuilder.createHashFromSnapshot(initSnapshot, None).createHash(prevHash)
        }
    }

    if (blockchainUpdater.isFeatureActivated(BlockchainFeatures.NG)) (Seq.empty, estimators.total, keyBlockStateHash)
    else {
      val mdConstraint                                       = MultiDimensionalMiningConstraint(estimators.total, estimators.keyBlock)
      val (maybeUnconfirmed, updatedMdConstraint, stateHash) = Instrumented.logMeasure(log, "packing unconfirmed transactions for block")(
        utx.packUnconfirmed(mdConstraint, keyBlockStateHash, PackStrategy.Limit(settings.minerSettings.microBlockInterval))
      )
      val unconfirmed = maybeUnconfirmed.getOrElse(Seq.empty)
      log.debug(s"Adding ${unconfirmed.size} unconfirmed transaction(s) to new block")
      (unconfirmed, updatedMdConstraint.head, stateHash)
    }
  }

  def forgeBlock(account: KeyPair, referenceOpt: Option[ByteStr] = None): ForgeAttemptResult = {
    // should take last block right at the time of mining since microblocks might have been added
    val reference = referenceOpt.getOrElse {
      val lastBlockHeader = blockchainUpdater.lastBlockHeader.get.header

      val maxMicroblockTimestampOffsetMs = // See min-micro-block-age in application.conf
        if (wallet.privateKeyAccount(lastBlockHeader.generator.toAddress).isRight) minMicroBlockDurationMills
        else 0L

      val lastBlockInfo = blockchainUpdater.bestLastBlockInfo(timeService.monotonicMillis() - maxMicroblockTimestampOffsetMs)
      lastBlockInfo.get.blockId
    }

    // Pinned to `reference` so every read below (generatingBalance, isMiningAllowed, isConflict,
    // nextBlockVersion, lastStateHash, isFeatureActivated, consensusData, blockFeatures) is consistent
    // with what block-append validation will reconstruct from the same reference -- the fix for the
    // miner/validator read-skew class of bug (upstream PR #4034). Task 8 (appender) and Task 9
    // (BlockchainUpdaterImpl) match this same pinned-read pattern.
    val blockchain     = blockchainUpdater.referencedBlockchain(reference)
    val refBlockHeader = blockchain.lastBlockHeader.get
    val refBaseTarget  = refBlockHeader.header.baseTarget
    val height         = blockchain.height
    val newBlockHeight = Height(height + 1)
    val version        = blockchain.nextBlockVersion

    val address = account.toAddress

    metrics.blockBuildTimeStats.measureSuccessful {
      val stopReasons = for {
        _ <- isAllowedForMiningByAccountScript(address, blockchain)
        balance = blockchain.generatingBalance(address)
        _ <- Either.raiseUnless(GeneratingBalanceProvider.isMiningAllowed(blockchain, newBlockHeight, balance)) {
          s"$address is not committed on $newBlockHeight. Try to commit to generation on next period"
        }
        _ <- Either.raiseWhen(blockchain.isConflict(newBlockHeight, address)) {
          s"$address is conflict on $newBlockHeight. Try to commit to generation on next period"
        }
      } yield balance

      def retryReasons(balance: Long) = for {
        _               <- checkQuorumAvailable()
        validBlockDelay <- pos
          .getValidBlockDelay(height, account, refBaseTarget, balance)
          .leftMap(_.toString)
        currentTime = timeService.correctedTime()
        blockTime   = math.max(
          refBlockHeader.header.timestamp + validBlockDelay,
          currentTime - 1.minute.toMillis
        )
        _ <- Either.cond(
          blockTime <= currentTime + maxTimeDrift,
          log.debug(s"Forging with $address, balance $balance, prev block $reference at $height with target $refBaseTarget"),
          s"Block time $blockTime is from the future: current time is $currentTime, MaxTimeDrift = $maxTimeDrift"
        )
        consensusData <- consensusData(blockchain, account, blockTime)
        prevStateHash =
          if (blockchain.isFeatureActivated(BlockchainFeatures.LightNode, newBlockHeight.toInt))
            Some(blockchain.lastStateHash(Some(reference)))
          else None
        (unconfirmed, totalConstraint, stateHash) = packTransactionsForKeyBlock(address, reference, prevStateHash)
        committedGeneratorsHash                   = {
          // A period boundary is the LAST height of a GenerationPeriod (period.end), not simply
          // "height % generationPeriodLength == 0". Periods are anchored to the DeterministicFinality
          // feature's ACTIVATION height (see GenerationPeriod.from), which is only guaranteed to be 0
          // on testnet (pre-activated at genesis); on any chain where the feature activates at a
          // non-zero, non-period-aligned height, plain modulo arithmetic checks the wrong heights
          // entirely and this would silently never validate.
          blockchain.generationPeriodOf(newBlockHeight).filter(_.end == newBlockHeight).map { period =>
            val validators = blockchain.committedGenerators(period.next).sortBy(_._1.toString)
            ByteStr(crypto.fastHash(validators.flatMap { case (addr, blsKey) => addr.bytes ++ blsKey.arr }.toArray))
          }
        }
        block <- Block
          .buildAndSign(
            version,
            blockTime,
            reference,
            consensusData.baseTarget,
            consensusData.generationSignature,
            unconfirmed,
            account,
            blockFeatures(blockchain, version),
            blockRewardVote(version),
            if (blockchain.supportsLightNodeBlockFields(newBlockHeight.toInt)) stateHash else None,
            challengedHeader = None,
            // A key block seals the current tip (reference) and extends it directly, so THIS
            // block's own reference is that tip's id -- the candidate that block-append validation
            // reconstructs from `block.header.reference`. That's a DIFFERENT candidate than what
            // `vote()` produces (the tip's own parent, matching what a microblock extending the tip
            // would need instead) -- so unlike MicroBlockMinerImpl (where every microblock in a liquid
            // period shares the SAME candidate and carrying forward a prior vote is correct), every key
            // block targets a brand new candidate; there is nothing valid to carry forward here.
            // blockEndorser.tryCollectSelf is the parallel round that targets the tip's own id
            // specifically -- use its result (see tryCollectSelfWithGrace for why a single immediate
            // attempt isn't enough), or None if still nothing after giving other nodes' endorsements a
            // fair chance to arrive (safe: matches pre-fix behavior).
            finalizationVoting = withHotStuffConflicts(tryCollectSelfWithGrace(reference)),
            committedGeneratorsHash = committedGeneratorsHash
          )
          .leftMap(_.err)
      } yield ForgeAttemptResult.Success(block, totalConstraint)

      stopReasons
        .leftMap(ForgeAttemptResult.PermanentFailure.apply)
        .flatMap { balance =>
          retryReasons(balance).leftMap(ForgeAttemptResult.TemporaryFailure.apply)
        }
    }.merge
  }

  // The self-target round's endorsedId is a brand new candidate every key block (unlike the
  // parent-target round, which stays live across an entire liquid period's worth of microblock
  // retries) -- this is the ONLY point anything ever collects for this specific candidate, so unlike
  // MicroBlockMinerImpl (which gets many chances at the same target as microblocks keep arriving),
  // there is no second attempt if this one is too early. Other committee members' endorsements for
  // THIS candidate only start propagating once they've each independently processed the same
  // just-appended tip, so a single immediate check races real network delivery time. Confirmed live:
  // this node's own endorsement was recorded within ~5ms of voting starting, but the actual miner for
  // the NEXT block (a different node, possibly on a different continent) can seal that next block
  // before the other two members' broadcasts finish propagating to it, permanently losing the chance
  // to embed a real quorum for this height. 1200ms matches the round-timeout already established
  // elsewhere in this codebase for the same Frankfurt<->Newark link (p99 round latency ~1000ms +
  // 20% margin); polling short-circuits as soon as something arrives, so this only ever adds latency
  // on the (safe, matches pre-fix behavior) fallback path where nothing arrives in time.
  private def tryCollectSelfWithGrace(endorsedId: BlockId): Option[FinalizationVoting] = {
    // Fast path: a disabled endorser (tests, the importer, the block generator) never collects
    // anything, so the grace poll below would burn the full 1200ms window on EVERY key-block forge
    // waiting for a guaranteed-None result -- pathologically slowing any miner-heavy suite. Production
    // always wires BlockEndorser.InMemory (enabled == true), so this skip changes no real forging or
    // finality behavior; it only removes dead waiting where nothing can ever arrive.
    if (!blockEndorser.enabled) return None

    // Widening this window does NOT fix the live testnet gap -- confirmed by testing 1200ms and
    // 8000ms side by side, both consistently return None (attempts maxed out, zero non-miner votes
    // collected). Root cause (confirmed via live debug logs, see project_finality_stall_recurrence
    // memory): the self-round's candidate is reset on EVERY microblock append (voteSelf() targets
    // the just-appended block's own id, which changes every microblock), so by the time a key block
    // is actually forged the live candidate has usually existed for well under a second -- nowhere
    // near enough for a real cross-datacenter 2-of-3 BLS vote round-trip, no matter how long we then
    // wait. Fixing this properly requires decoupling self-round vote accumulation from per-microblock
    // resets (e.g. retroactive credit for a superseded candidate), which is a real design change, not
    // a timing tweak -- scoped out as follow-up work. Kept at 1200ms (the node-it-verified bound,
    // where the local low-latency test network converges within it) rather than the wider diagnostic
    // value, since a longer wait has no live benefit and only delays block production for nothing.
    val deadline       = System.currentTimeMillis() + 1200
    val pollIntervalMs = 100
    var attempts       = 1
    var result         = blockEndorser.tryCollectSelf(endorsedId)
    while (result.isEmpty && System.currentTimeMillis() < deadline) {
      Thread.sleep(pollIntervalMs)
      attempts += 1
      result = blockEndorser.tryCollectSelf(endorsedId)
    }
    log.debug(s"tryCollectSelfWithGrace($endorsedId): attempts=$attempts result=$result")
    result
  }

  // T5 rev.2 (docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md §5):
  // production-side slashingEnabled gate. Folds pending HotStuff equivocation proofs detected by
  // this replica's coordinator into the key block's FinalizationVoting, so they get union'd into
  // committedGenerators/conflictGenerators on append (validation/union elsewhere are unconditional).
  private def withHotStuffConflicts(voting: Option[FinalizationVoting]): Option[FinalizationVoting] = {
    val forgeHeight = Height(blockchainUpdater.height + 1)
    // Validation (appender/package.scala:364, via validateHotStuffEquivocationProofs) rejects any
    // block whose FinalizationVoting carries hotstuffConflicts before feature 29
    // (HotStuffEquivocationEvidence) is active AT THE BLOCK HEIGHT -- a strictly later gate than
    // feature 28 (DeterministicFinality, checked by generationPeriodOf below). Composing proofs
    // during the 28-active-but-29-inactive window would forge a block this same node's own
    // appender then rejects on append: a pure self-DoS. Skip the fold entirely until 29 is live.
    if (!blockchainUpdater.supportsHotStuffEquivocationEvidence(forgeHeight.toInt)) voting
    else
      blockchainUpdater.generationPeriodOf(forgeHeight) match {
        case None         => voting // pre-activation: no periods, no committee, nothing to fold
        case Some(period) =>
          Miner.foldHotStuffConflicts(
            settings.hotStuffSettings.slashingEnabled,
            hotStuffEquivocations(),
            voting,
            period.index,
            idx => blockchainUpdater.conflictGenerators(period).upTo(forgeHeight).contains(GeneratorIndex(idx)),
            () => Miner.clampFinalizedHeight(blockchainUpdater.finalizedHeight.getOrElse(GenesisBlockHeight), blockchainUpdater.height)
          )
      }
  }

  private def checkQuorumAvailable(): Either[String, Int] =
    Right(allChannels.size())
      .ensureOr(chanCount => s"Quorum not available ($chanCount/${minerSettings.quorum}), not forging block.")(_ >= minerSettings.quorum)

  private def blockFeatures(blockchain: Blockchain, version: Byte): Seq[Short] =
    if (version <= PlainBlockVersion) Nil
    else {
      val exclude = blockchain.approvedFeatures.keySet ++ settings.blockchainSettings.functionalitySettings.preActivatedFeatures.keySet

      settings.featuresSettings.supported
        .filterNot(exclude)
        .filter(BlockchainFeatures.implemented)
        .sorted
    }

  private def blockRewardVote(version: Byte): Long =
    if (version < RewardBlockVersion) -1L
    else settings.rewardsSettings.desired.getOrElse(-1L)

  def nextBlockGenerationTime(blockchain: Blockchain, block: SignedBlockHeader, account: KeyPair): Either[String, Long] = {
    val balance = blockchain.generatingBalance(account.toAddress, Some(block.id()))

    if (GeneratingBalanceProvider.isMiningAllowed(blockchain, Height(blockchain.height), balance)) {
      val blockDelayE = pos.copy(blockchain = blockchain).getValidBlockDelay(blockchain.height, account, block.header.baseTarget, balance)
      for {
        delay <- blockDelayE.leftMap(_.toString)
        expectedTS = delay + block.header.timestamp
        _ <- Either.raiseUnless(0 < expectedTS && expectedTS < Long.MaxValue) {
          s"Invalid next block generation time: $expectedTS"
        }
      } yield expectedTS
    } else Left(s"Balance $balance of ${account.toAddress} is lower than required for generation")
  }

  private def nextBlockGenOffsetWithConditions(account: KeyPair, blockchain: Blockchain): Either[String, FiniteDuration] = {
    val height      = blockchain.height
    val lastBlock   = blockchain.lastBlockHeader.get
    val prevBlockTs = lastBlock.header.timestamp
    for {
      _           <- checkAge(height, prevBlockTs)
      _           <- isAllowedForMiningByAccountScript(account.toAddress, blockchain)
      nextBlockTs <- nextBlockGenerationTime(blockchain, lastBlock, account)
      minNextBlockTs  = if (height == 1) 0L else prevBlockTs + minerSettings.minimalBlockGenerationOffset.toMillis
      adjustedBlockTs = nextBlockTs.max(minNextBlockTs)
      offset          = 0L.max(adjustedBlockTs - timeService.correctedTime()).millis
    } yield offset
  }

  private[mining] def generateBlockTask(account: KeyPair, maybeBlockchain: Option[Blockchain]): Task[Unit] = {
    (for {
      offset <- nextBlockGenOffsetWithConditions(account, maybeBlockchain.getOrElse(blockchainUpdater))
      quorumAvailable = checkQuorumAvailable().isRight
    } yield {
      if (quorumAvailable) offset
      else offset.max(settings.minerSettings.noQuorumMiningDelay)
    }) match {
      case Right(offset) =>
        log.debug(
          f"Next attempt for acc=${account.toAddress} in ${offset.toUnit(SECONDS)}%.3f seconds (${LocalTime.now().plusNanos(offset.toNanos)})"
        )

        val waitBlockAppendedTask = maybeBlockchain match {
          case Some(value) =>
            def waitUntilBlockAppended(block: BlockId): Task[Unit] =
              if (blockchainUpdater.contains(block)) Task.unit
              else Task.defer(waitUntilBlockAppended(block)).delayExecution(1 seconds)

            waitUntilBlockAppended(value.lastBlockId.get)

          case None => Task.unit
        }

        def appendTask(
            block: Block,
            totalConstraint: MiningConstraint
        ) = // NOTE: Could accept blockAppender to reduce parameter count — current pattern works correctly
          BlockAppender(blockchainUpdater, timeService, utx, pos, blockEndorser, appenderScheduler)(block, None).flatMap {
            case Left(BlockFromFuture(_, _)) => // Time was corrected, retry
              generateBlockTask(account, None)

            case Left(err) =>
              Task.raiseError(new RuntimeException(err.toString))

            case Right(Applied(score = score)) =>
              log.debug(s"Forged and applied $block with cumulative score $score")
              BlockStats.mined(block, blockchainUpdater.height)
              if (blockchainUpdater.isLastBlockId(block.id())) {
                allChannels.broadcast(BlockForged(block))
                if (ngEnabled && !totalConstraint.isFull) startMicroBlockMining(account, block, totalConstraint)
              }
              Task.unit

            case Right(Ignored) =>
              Task.raiseError(new RuntimeException("Newly created block has already been appended, should not happen"))
          }.uncancelable

        for {
          elapsed <- waitBlockAppendedTask.timed.map(_._1)
          newOffset = (offset - elapsed).max(Duration.Zero)

          _      <- Task.sleep(newOffset)
          result <- Task(forgeBlock(account)).executeOn(minerScheduler)

          _ <- result match {
            case ForgeAttemptResult.Success(block, restConstraint) =>
              appendTask(block, restConstraint)

            case ForgeAttemptResult.TemporaryFailure(err) =>
              log.debug(s"No block generated because $err, retrying")
              generateBlockTask(account, None)

            case ForgeAttemptResult.PermanentFailure(err) =>
              log.debug(s"No block generated because $err, stopping")
              Task.unit
          }
        } yield ()

      case Left(err) =>
        log.debug(s"Not scheduling block mining because $err")
        debugStateRef = MinerDebugInfo.Error(err)
        Task.unit
    }
  }

  private def startMicroBlockMining(
      account: KeyPair,
      lastBlock: Block,
      restTotalConstraint: MiningConstraint
  ): Unit = {
    Miner.microMiningStarted.increment()
    microBlockAttempt := microBlockMiner
      .generateMicroBlockSequence(account, lastBlock, restTotalConstraint, 0)
      .runAsyncLogErr(using minerScheduler)
    log.trace(s"MicroBlock mining scheduled for acc=${account.toAddress}")
  }

  // noinspection TypeAnnotation,ScalaStyle
  private object metrics {
    val blockBuildTimeStats = Kamon.timer("miner.pack-and-forge-block-time").withoutTags()
  }
}

object Miner {
  private[mining] val blockMiningStarted = Kamon.counter("block-mining-started").withoutTags()
  private[mining] val microMiningStarted = Kamon.counter("micro-mining-started").withoutTags()

  val MaxTransactionsPerMicroblock: Int = 500

  val StrictDisabledMiner: Miner & MinerDebugInfo = new Miner with MinerDebugInfo {
    override def scheduleMining(baseBlockchain: Option[Blockchain], cancelMicroBlockMining: Boolean): Unit = {}
    override def getNextBlockGenerationOffset(account: KeyPair): Either[String, FiniteDuration]        = Left("Disabled")
    override val state: MinerDebugInfo.State                                                           = MinerDebugInfo.Disabled
  }

  def forwardTo(underlying: => Miner): Miner = { (baseBlockchain: Option[Blockchain], cancelMicroBlockMining: Boolean) =>
    underlying.scheduleMining(baseBlockchain, cancelMicroBlockMining)
  }

  def isAllowedForMiningByAccountScript(address: Address, blockchain: Blockchain): Either[String, Unit] =
    Either.cond(
      blockchain.isFeatureActivated(BlockchainFeatures.RideV6) || !blockchain.hasAccountScript(address),
      (),
      s"Account($address) is scripted and not allowed to forge blocks"
    )

  /** T5 rev.2: fold pending verified equivocation proofs into the key block's FinalizationVoting.
    * PRODUCTION-side gate only (spec §5): validation/union on receipt are unconditional elsewhere.
    * Filters: epoch must equal the forge height's generation-period index (validation would reject
    * anything else -- rule 3), voter not already excluded on-chain (validation rule 5), dedup by voter.
    * `fallbackFinalizedHeight` is evaluated only when synthesizing an FV from nothing.
    */
  private[mining] def foldHotStuffConflicts(
      slashingEnabled: Boolean,
      pending: Seq[HotStuffEquivocationProof],
      voting: Option[FinalizationVoting],
      forgeHeightPeriodIndex: Int,
      alreadyExcluded: Int => Boolean,
      fallbackFinalizedHeight: () => Height
  ): Option[FinalizationVoting] = {
    val usable =
      if (!slashingEnabled) Seq.empty
      else
        pending
          .filter(p => p.committeeEpoch == forgeHeightPeriodIndex && !alreadyExcluded(p.voterIndex))
          .distinctBy(_.voterIndex)
    if (usable.isEmpty) voting
    else
      voting match {
        case Some(fv) =>
          // Validation rejects any hotstuffConflicts proof whose voterIndex already carries a T0
          // conflicting endorsement in the SAME voting (appender/package.scala:382-384, rule 6) --
          // fv.conflict is populated by the endorsement-collection path this fold runs after, so a
          // proof for a voter already present there must be dropped here too, or the composed block
          // fails that same validator rule on this node's own append.
          val conflictingVoters = fv.conflict.map(_.endorserIndex.toInt).toSet
          val stillUsable       = usable.filterNot(p => conflictingVoters.contains(p.voterIndex))
          if (stillUsable.isEmpty) voting
          else Some(fv.copy(hotstuffConflicts = (fv.hotstuffConflicts ++ stillUsable).distinctBy(_.voterIndex)))
        case None => Some(FinalizationVoting(Seq.empty, fallbackFinalizedHeight(), None, Seq.empty, usable))
      }
  }

  /** Validation requires `fv.finalizedHeight.toInt < blockchain.height` (appender/package.scala:401,
    * evaluated while the chain tip is still at the parent height H that the miner read at forge
    * time) -- i.e. the synthesized value must land in `[GenesisBlockHeight, H-1]`. The raw
    * `blockchainUpdater.finalizedHeight` fallback can equal H itself once `hotstuff.authoritative`
    * has raised the finalized floor to the tip, which would forge a block this node's own appender
    * then rejects. Extracted as a small pure function (private[mining] for direct unit coverage)
    * rather than folded inline, since the pure `foldHotStuffConflicts` fold must stay agnostic to
    * "current chain height" and just pass through whatever fallback it's given.
    */
  private[mining] def clampFinalizedHeight(rawFallback: Height, currentHeight: Int): Height =
    Height(rawFallback.toInt.min(currentHeight - 1).max(GenesisBlockHeight.toInt))
}
