package com.decentralchain

import cats.Eq
import cats.instances.bigInt.*
import cats.syntax.option.*
import com.typesafe.config.*
import com.typesafe.scalalogging.Logger
import com.decentralchain.account.AddressScheme
import com.decentralchain.actor.RootActorSystem
import com.decentralchain.api.BlockMeta
import com.decentralchain.api.common.*
import com.decentralchain.api.http.*
import com.decentralchain.api.http.alias.AliasApiRoute
import com.decentralchain.api.http.assets.AssetsApiRoute
import com.decentralchain.api.http.eth.EthRpcRoute
import com.decentralchain.api.http.leasing.LeaseApiRoute
import com.decentralchain.api.http.utils.UtilsApiRoute
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.PoSSelector
import com.decentralchain.database.{DBExt, Keys, RDB}
import com.decentralchain.events.{BlockchainUpdateTriggers, UtxEvent}
import com.decentralchain.extensions.{Context, Extension}
import com.decentralchain.features.EstimatorProvider.*
import com.decentralchain.features.api.ActivationApiRoute
import com.decentralchain.history.{History, StorageFactory}
import com.decentralchain.lang.ValidationError
import com.decentralchain.metrics.Metrics
import com.decentralchain.mining.{BlockChallengerImpl, Miner, MinerDebugInfo, MinerImpl}
import com.decentralchain.network.*
import com.decentralchain.settings.DCCSettings
import com.decentralchain.state.appender.{BlockAppender, ExtensionAppender, MicroblockAppender}
import com.decentralchain.state.{BlockEndorser, BlockRewardCalculator, Blockchain, CompleteBlockchainUpdater, EndorsementStorage, Height, TxMeta}
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.smart.script.trace.TracedResult
import com.decentralchain.transaction.{DiscardedBlocks, Transaction}
import com.decentralchain.utils.*
import com.decentralchain.utils.Schedulers.*
import com.decentralchain.utx.{UtxPool, UtxPoolImpl}
import com.decentralchain.wallet.Wallet
import io.netty.channel.Channel
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.HashedWheelTimer
import io.netty.util.concurrent.{DefaultThreadFactory, GlobalEventExecutor}
import kamon.Kamon
import kamon.instrumentation.executor.ExecutorInstrumentation
import monix.eval.{Coeval, Task}
import monix.execution.schedulers.{ExecutorScheduler, SchedulerService}
import monix.execution.{ExecutionModel, Scheduler, UncaughtExceptionReporter}
import monix.reactive.Observable
import monix.reactive.subjects.ConcurrentSubject
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.influxdb.dto.Point
import org.rocksdb.RocksDB
import org.slf4j.LoggerFactory

import java.io.File
import java.security.Security
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{TimeUnit, *}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class Application(val actorSystem: ActorSystem, val settings: DCCSettings, configRoot: ConfigObject, time: NTP) extends ScorexLogging {
  app =>

  import Application.*
  import monix.execution.Scheduler.Implicits.global as scheduler

  private val rdb = RDB.open(settings.dbSettings)

  private val wallet: Wallet = Wallet(settings.walletSettings)

  private val peerDatabase = new PeerDatabaseImpl(settings.networkSettings)

  // This handler is needed in case Fatal exception is thrown inside the task

  private val stopOnAppendError = UncaughtExceptionReporter { cause =>
    log.error("Error in Appender", cause)
    forceStopApplication(FatalDBError)
  }

  private val appenderScheduler = singleThread("appender", stopOnAppendError)

  private val extensionLoaderScheduler        = singleThread("rx-extension-loader", reporter = log.error("Error in Extension Loader", _))
  private val microblockSynchronizerScheduler =
    singleThread("microblock-synchronizer", reporter = log.error("Error in Microblock Synchronizer", _))
  private val endorseBlockSynchronizerScheduler =
    singleThread("endorseblock-synchronizer", reporter = log.error("Error in EndorseBlock Synchronizer", _))
  private val scoreObserverScheduler  = singleThread("rx-score-observer", reporter = log.error("Error in Score Observer", _))
  private val historyRepliesScheduler = fixedPool(poolSize = 2, "history-replier", reporter = log.error("Error in History Replier", _))
  private val minerScheduler          = singleThread("block-miner", reporter = log.error("Error in Miner", _))
  private val hotStuffScheduler       = singleThread("hotstuff-coordinator", reporter = log.error("Error in HotStuff coordinator", _))

  private val utxEvents = ConcurrentSubject.publish[UtxEvent](using scheduler)

  private var extensions = Seq.empty[Extension]

  private var triggers = Seq.empty[BlockchainUpdateTriggers]

  private var miner: Miner & MinerDebugInfo = Miner.StrictDisabledMiner
  private val (blockchainUpdater, rocksDB)  =
    StorageFactory(settings, rdb, time, BlockchainUpdateTriggers.combined(triggers), Miner.forwardTo(miner))

  private val messageObserver = new MessageObserver

  @volatile
  private var maybeUtx: Option[UtxPool] = None

  @volatile
  private var maybeNetworkServer: Option[NetworkServer] = None

  @volatile
  private var serverBinding: ServerBinding = compiletime.uninitialized

  def run(): Unit = {
    // initialization
    implicit val as: ActorSystem = actorSystem

    if (wallet.privateKeyAccounts.isEmpty)
      wallet.generateNewAccounts(1)

    val establishedConnections = new ConcurrentHashMap[Channel, PeerInfo]
    val allChannels            = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    val utxStorage             =
      new UtxPoolImpl(time, blockchainUpdater, settings.utxSettings, settings.maxTxErrorLogSize, settings.minerSettings.enable, utxEvents.onNext)
    maybeUtx = Some(utxStorage)

    val timer                 = new HashedWheelTimer()
    val utxSynchronizerLogger = Logger(LoggerFactory.getLogger(classOf[TransactionPublisher]))
    val timedTxValidator      =
      Schedulers.timeBoundedFixedPool(
        timer,
        5.seconds,
        settings.synchronizationSettings.utxSynchronizer.maxThreads,
        "utx-time-bounded-tx-validator",
        reporter = utxSynchronizerLogger.trace("Uncaught exception in UTX Synchronizer", _)
      )

    val knownInvalidBlocks = new InvalidBlockStorageImpl(settings.synchronizationSettings.invalidBlocksStorage)

    val pos = PoSSelector(blockchainUpdater, settings.synchronizationSettings.maxBaseTarget)

    val endorsementStorage = EndorsementStorage.InMemory((blockId, height) => blockchainUpdater.blockId(height.toInt).contains(blockId))
    // Separate tracker for the self-target round (BlockEndorser.voteSelf/tryCollectSelf) -- a distinct
    // candidate from endorsementStorage's parent-target round (see BlockEndorser's docs), so it needs
    // its own independent voting state rather than sharing/overwriting the same slot.
    val selfEndorsementStorage = EndorsementStorage.InMemory((blockId, height) => blockchainUpdater.blockId(height.toInt).contains(blockId))
    val blockEndorser          =
      new BlockEndorser.InMemory(
        settings.synchronizationSettings.maxRollback,
        blockchainUpdater,
        wallet,
        endorsementStorage,
        selfEndorsementStorage,
        allChannels
      )

    if (settings.minerSettings.enable)
      miner = new MinerImpl(
        allChannels,
        blockchainUpdater,
        settings,
        time,
        utxStorage,
        blockEndorser,
        endorsementStorage,
        wallet,
        pos,
        minerScheduler,
        appenderScheduler,
        utxEvents.collect { case _: UtxEvent.TxAdded =>
          ()
        }
      )

    val blockChallenger =
      if (settings.minerSettings.enable && !settings.enableLightMode) {
        Some(
          new BlockChallengerImpl(
            blockchainUpdater,
            allChannels,
            wallet,
            settings,
            time,
            pos,
            appendBlock = BlockAppender(blockchainUpdater, time, utxStorage, pos, blockEndorser, appenderScheduler)(_, None)
          )
        )
      } else None

    val processBlock =
      BlockAppender(blockchainUpdater, time, utxStorage, pos, allChannels, peerDatabase, blockChallenger, blockEndorser, appenderScheduler)

    val processFork =
      ExtensionAppender(blockchainUpdater, utxStorage, pos, time, knownInvalidBlocks, peerDatabase, appenderScheduler)
    val processMicroBlock =
      MicroblockAppender(blockchainUpdater, utxStorage, allChannels, peerDatabase, blockChallenger, blockEndorser, appenderScheduler)

    import blockchainUpdater.lastBlockInfo

    val lastScore = lastBlockInfo
      .map(_.score)
      .distinctUntilChanged
      .share(using scheduler)

    lastScore
      .debounce(1.second)
      .foreach { x =>
        allChannels.broadcast(LocalScoreChanged(x))
      }(using scheduler)

    // ---- T2 HotStuff (gated behind dcc.hotstuff.enabled; observational commit). ----
    // See docs/hotstuff-integration-design.md. Skipped entirely when disabled (the default) => zero
    // behaviour change. When enabled: a single-thread scheduler confines all coordinator state; the
    // committee is read fresh per period. Per-height happy path: view = settled block height, leader =
    // FairPoS forger. On a leader-timeout, the pacemaker can ALSO advance the view independently of
    // height (see `blockSource`/`onRoundTimerTick` below) -- `proposalValid`/vote-height derivation are
    // written to not assume view == height (Task 8 Step 2, docs/hotstuff-step5-findings-and-rework.md
    // §4 Option A). NOT validated on a running multi-node network yet (that is step 5) -- must not be
    // enabled on mainnet before step 5 + an external audit. Commit is observational only (feature-25
    // stays authoritative).
    if (settings.hotStuffSettings.enabled) {
      import com.decentralchain.consensus.hotstuff.{HotStuffCoordinator, NodeHotStuffEffects}
      import com.decentralchain.block.Block.BlockId
      import com.decentralchain.state.GeneratorSet

      val committee: () => GeneratorSet                = () => blockchainUpdater.currentGeneratorSet.getOrElse(Seq.empty)
      val extendsBranch: (BlockId, BlockId) => Boolean = (child, ancestor) =>
        child == ancestor || (for {
          hc <- blockchainUpdater.heightOf(child)
          ha <- blockchainUpdater.heightOf(ancestor)
        } yield hc >= ha).getOrElse(false)

      // Replica safety guard: does `blockId` live anywhere on OUR OWN canonical chain? Deliberately NOT
      // keyed on `view` (Task 8 Step 2 follow-up; see docs/hotstuff-step5-findings-and-rework.md §4
      // Option A and consensus/hotstuff-pacemaker-rework's RED test in HotStuffViewChangeSpecification).
      // The previous guard was `blockchainUpdater.blockId(view).contains(blockId)`, i.e. "blockId is
      // literally the canonical block AT HEIGHT == view" -- true in the per-height happy path (one view
      // per settled height, below) but false the instant a pacemaker-driven view-change advances `view`
      // independently of height, silently dropping a legitimate re-proposal of an already-canonical
      // block (exactly findings #2/#5's height/view conflation bug class, reintroduced here). Chain
      // membership of `blockId` alone answers the real question -- "is this a real block on MY chain" --
      // without assuming anything about `view`. View-ordering/lock safety (can we vote in this view;
      // does this extend our locked branch or carry a newer justify-QC) is enforced separately,
      // unconditionally, by `HotStuffSafety.safeToVote` inside `HotStuffEngine.onProposal`, which runs
      // AFTER this guard passes -- this guard's sole job is rejecting proposals for blocks we don't
      // independently recognize as real, not reasoning about view numbers.
      val proposalValid: BlockId => Boolean =
        blockId => blockchainUpdater.heightOf(blockId).exists(h => blockchainUpdater.blockId(h).contains(blockId))

      // FALLBACK for what to (re-)propose if a leader-timeout view-change (Task 8 Step 2) makes THIS
      // node the newly rotated leader and it has NOTHING already in flight. Proposes the current settled
      // tip -- the SAME block the per-height happy path below would propose next -- so a pacemaker-driven
      // proposal is never for a different/newer block than the happy path would pick; it only differs in
      // WHEN and under WHICH view number it fires (reacting to a stalled round instead of a height
      // increment).
      //
      // `HotStuffCoordinator.Enabled.onRoundTimerTick` now prefers re-proposing a real, quorum-backed,
      // not-yet-committed branch it already holds (`SafetyState.prepareQC`, exposed internally as
      // `inFlightBranch`) over calling this `blockSource` at all -- the classic HotStuff pacemaker
      // liveness optimization this comment used to defer as a "real design question left as follow-up".
      // No new API surface was needed: `prepareQC`/`committedHeight` already existed in
      // `SafetyState`/`EngineState` before this change; the gap was only that the leader-timeout path
      // never consulted them. This `blockSource` closure is consulted ONLY when nothing is in flight
      // (the common, clean-timeout case) -- see HotStuffCoordinator.scala's `inFlightBranch`/
      // `onRoundTimerTick` and HotStuffViewChangeSpecification's "onRoundTimerTick's leader-timeout
      // re-propose choice" test.
      val blockSource: () => Option[(BlockId, Int)] = () => {
        val tip = blockchainUpdater.height
        val s   = tip - settings.hotStuffSettings.settledDepth
        if (s > 0) blockchainUpdater.blockId(s).map(id => (id, s)) else None
      }

      // TESTNET-ONLY opt-in (see HotStuffSettings.authoritative doc + docs/hotstuff-audit-readiness.md):
      // when true, a genuine commit ALSO raises the authoritative feature-25 finalizedHeight via
      // `blockchainUpdater.raiseHotStuffFinalizedHeight`, which independently re-checks the certified
      // block against this node's own canonical chain before ever applying anything. Defaults to false
      // (purely observational, today's unchanged behaviour) -- must never be set true on mainnet ahead
      // of the external audit.
      val hsEffects = new NodeHotStuffEffects(
        committee,
        wallet,
        allChannels,
        authoritative = settings.hotStuffSettings.authoritative,
        raiseFinalizedHeight = (id, h) => blockchainUpdater.raiseHotStuffFinalizedHeight(id, com.decentralchain.state.Height(h))
      )

      // Closes the post-restart `lockedQC=None` window (see `HotStuffSafety.safeToVote`'s doc comment
      // and `HotStuffLockedQCStore`): reload this replica's last-persisted lock at startup, and persist
      // every subsequent advance so the NEXT restart also resumes from a real lock instead of nothing.
      // Lives under the node's own data directory (like `dcc.db.directory`), so it is never touched
      // while `dcc.hotstuff.enabled = false` -- this whole block only runs when enabled.
      import com.decentralchain.consensus.hotstuff.HotStuffLockedQCStore
      val hsLockedQCPath    = java.nio.file.Paths.get(settings.directory, "hotstuff", "locked-qc.dat")
      val hsInitialLockedQC = HotStuffLockedQCStore.load(hsLockedQCPath)
      // `heightOf` lets the self-vote path (`onLeaderTurn`) independently re-derive a block's height
      // from its blockId, the same defense-in-depth the receive path below already applies via
      // `blockchainUpdater.heightOf(p.blockId)`, instead of trusting `blockSource`'s returned height
      // literally.
      val hsCoordinator =
        new HotStuffCoordinator.Enabled(
          committee,
          hsEffects,
          extendsBranch,
          proposalValid,
          blockSource,
          blockchainUpdater.heightOf,
          hsInitialLockedQC,
          qc => HotStuffLockedQCStore.save(hsLockedQCPath, qc)
        )

      // HotStuff messages must reach ALL committed generators, not just directly-connected peers.
      // `allChannels.broadcast` only sends to direct peers, so in a non-full-mesh topology (e.g. gen
      // nodes bridged only via the main node) a validator sees <2/3 of votes and no QC ever forms.
      // Like feature-25 endorsements, GOSSIP them: on first receipt relay to every peer except the
      // sender, then process. A bounded seen-cache dedups so relaying cannot storm.
      import com.google.common.cache.{Cache, CacheBuilder}
      import io.netty.channel.Channel
      import com.decentralchain.network.Message
      val hsSeen: Cache[String, java.lang.Boolean]                          = CacheBuilder.newBuilder().maximumSize(100000).build()
      def hsGossipOnce(sender: Channel, key: String, msg: Message): Boolean =
        if (hsSeen.getIfPresent(key) != null) false
        else {
          hsSeen.put(key, java.lang.Boolean.TRUE)
          allChannels.broadcast(msg, Set(sender)) // relay to all other peers (transitive delivery)
          true
        }

      messageObserver.hotStuffProposals
        .observeOn(hotStuffScheduler)
        .foreach { case (ch, p) =>
          // blockHeight MUST be independently derived from the proposal's OWN blockId
          // (`blockchainUpdater.heightOf(p.blockId)`), NOT taken from `p.view`. In the per-height happy
          // path view == the block's settled height so the two happened to agree, but that equality is
          // exactly the assumption a pacemaker-driven view-change breaks (view can be strictly greater
          // than the re-proposed block's real height). Deriving height from blockId -- the same way the
          // leader's own `blockSource`/happy-path height is derived, and the same way `proposalValid`
          // above independently re-derives it -- guarantees every honest replica computes the IDENTICAL
          // blockHeight for a given blockId regardless of which view it arrives under, which is what
          // `HotStuffQuorum.formQC` requires (an identical blockHeight across all votes for a target) --
          // this is precisely the invariant finding #5 needed once already (there, a replica used its
          // local tip instead of the settled view; here, deriving from blockId instead of view prevents
          // that whole class of mismatch by construction rather than by convention). Falls back to
          // `p.view` only when we don't recognize the block at all (heightOf is None) -- in that case
          // `proposalValid` above has already rejected the proposal via the same lookup, so this
          // fallback value is never actually consumed by a cast vote.
          if (hsGossipOnce(ch, s"p:${p.view}:${p.blockId}", p))
            hsCoordinator.onProposal(p, blockchainUpdater.heightOf(p.blockId).getOrElse(p.view))
        }(using hotStuffScheduler)
      messageObserver.hotStuffVotes
        .observeOn(hotStuffScheduler)
        .foreach { case (ch, v) =>
          if (hsGossipOnce(ch, s"v:${v.view}:${v.phase}:${v.blockId}:${v.voterIndex}", v)) hsCoordinator.onVote(v)
        }(using hotStuffScheduler)
      messageObserver.hotStuffQCs
        .observeOn(hotStuffScheduler)
        .foreach { case (ch, qc) =>
          if (hsGossipOnce(ch, s"q:${qc.view}:${qc.phase}:${qc.blockId}", qc)) hsCoordinator.onQC(qc)
        }(using hotStuffScheduler)

      // HotStuff-over-FairPoS: run one SETTLED height behind the tip so every node agrees on exactly one
      // canonical block per view. view = settled height s = tip-1; the proposed block is the canonical
      // key-block id `blockchainUpdater.blockId(s)` — stable once s is no longer the tip, and identical
      // on every node — NOT the liquid `lastBlockInfo.id`, which changes as microblocks append and
      // diverges across nodes (that divergence made votes fragment across blockIds so no QC ever formed).
      // Leader(s) = the FairPoS generator of block s; only that node proposes. Trigger once per height
      // increment (microblock updates at the same height carry the same height and are ignored).
      var hsLastHeight = 0
      lastBlockInfo
        .observeOn(hotStuffScheduler)
        .foreach { info =>
          val tip = info.height.toInt
          if (tip > hsLastHeight) {
            hsLastHeight = tip
            // Run `settledDepth` blocks behind the tip so every node has SETTLED s (final key-block id,
            // not a liquid tip that still differs across nodes) before it is proposed — else the
            // canonical-block guard rejects and votes never converge. See HotStuffSettings.settledDepth.
            val s = tip - settings.hotStuffSettings.settledDepth
            if (s > 0) {
              for {
                canonicalId <- blockchainUpdater.blockId(s)
                header      <- blockchainUpdater.blockHeader(s)
              } {
                val weAreLeader = wallet.privateKeyAccount(header.header.generator.toAddress).isRight
                log.debug(s"[HotStuff] settled view=$s leader=${header.header.generator.toAddress} weAreLeader=$weAreLeader block=$canonicalId")
                if (weAreLeader) hsCoordinator.onLeaderTurn(s, canonicalId, s)
              }
            }
          }
        }(using hotStuffScheduler)

      // Pacemaker: advance the view on timeout. FairPoS + feature-25 continue underneath -> never halts.
      // Uses `onRoundTimerTick` (Task 8 Step 2 rework), which now genuinely detects a stalled round (no
      // QC formed since the previous tick) rather than treating every tick as a timeout -- see
      // HotStuffCoordinator.onRoundTimerTick and docs/hotstuff-step5-findings-and-rework.md §4 Option A.
      //
      // `blockSource` (above) IS now wired for real: on a genuine leader-timeout, if this replica is the
      // newly-rotated leader, it (re-)proposes the current settled tip under the new (higher) view
      // number. This is now safe because `proposalValid` (above) no longer assumes view == height --
      // it checks chain-membership of the blockId alone -- so a proposal whose view exceeds its block's
      // real height (the normal outcome of a pacemaker view-change) is no longer silently rejected.
      // `HotStuffQuorum.formQC` still requires every vote for a given (view, phase, blockId) target to
      // carry an IDENTICAL blockHeight; both this leader-timeout path and the message-observer's receive
      // path (above) derive that height the same way -- `blockchainUpdater.heightOf(blockId)` -- so
      // every honest replica computes the same value regardless of which path or view triggered the vote.
      //
      // Safety reasoning for enabling this on a live network (testnet has `dcc.hotstuff.enabled=true`
      // today): T2 remains STRICTLY OBSERVATIONAL here -- `NodeHotStuffEffects.onCommit` only advances
      // `hotStuffFinalizedHeight`, a status field; feature-25 Deterministic Finality remains the sole
      // authoritative finality source and is untouched by any of this. Every vote/QC this change can
      // possibly cause is still gated by the SAME unconditional safety machinery as the happy path:
      // `HotStuffSafety.safeToVote` (view/lock ordering) and `HotStuffQuorum.verifyQC`/`formQC`
      // (cryptographic + identical-height quorum). The worst case a pacemaker-driven proposal can cause
      // if replicas' local view counters diverge (each node's timer/QC history can differ slightly) is
      // that votes for that specific (view, blockId) target never reach quorum -- a liveness/no-QC
      // outcome, not a safety one -- functionally identical to any other non-quorate HotStuff round
      // today. It cannot fabricate a commit, and cannot regress feature-25. This must still be re-audited
      // (docs/hotstuff-audit-readiness.md) before HotStuff is ever made mainnet-authoritative.
      val rt = settings.hotStuffSettings.roundTimeout.toMillis
      hotStuffScheduler.scheduleWithFixedDelay(rt, rt, java.util.concurrent.TimeUnit.MILLISECONDS, () => hsCoordinator.onRoundTimerTick())
      log.info(
        s"T2 HotStuff coordinator ENABLED (observational; view=settled height, settled-depth=${settings.hotStuffSettings.settledDepth}). Not audited/soaked — testnet only."
      )
    }

    val history = History(
      blockchainUpdater,
      blockchainUpdater.liquidBlock,
      blockchainUpdater.microBlock,
      blockchainUpdater.liquidBlockSnapshot,
      blockchainUpdater.microBlockSnapshot,
      rdb
    )

    val historyReplier = new HistoryReplier(blockchainUpdater.score, history, settings.synchronizationSettings)(using historyRepliesScheduler)

    val transactionPublisher =
      TransactionPublisher.timeBounded(
        utxStorage.putIfNew,
        allChannels.broadcast,
        timedTxValidator,
        settings.synchronizationSettings.utxSynchronizer.allowTxRebroadcasting,
        () =>
          if (allChannels.size >= settings.restAPISettings.minimumPeers) Right(())
          else Left(GenericError(s"There are not enough connections with peers (${allChannels.size}) to accept transaction"))
      )

    def rollbackTask(blockId: ByteStr, returnTxsToUtx: Boolean) =
      Task {
        utxStorage.resetPriorityPool()
        blockchainUpdater.removeAfter(blockId)
      }.executeOn(appenderScheduler)
        .map {
          case Right(discardedBlocks) =>
            allChannels.broadcast(LocalScoreChanged(blockchainUpdater.score))
            if (returnTxsToUtx) utxStorage.addAndScheduleCleanup(discardedBlocks.view.flatMap(_._1.transactionData))
            Right(discardedBlocks)
          case Left(error) => Left(error)
        }

    // Extensions start
    val extensionContext: Context = new Context {
      override def settings: DCCSettings                                                         = app.settings
      override def blockchain: Blockchain                                                        = app.blockchainUpdater
      override def rollbackTo(blockId: ByteStr): Task[Either[ValidationError, DiscardedBlocks]]  = rollbackTask(blockId, returnTxsToUtx = false)
      override def time: Time                                                                    = app.time
      override def wallet: Wallet                                                                = app.wallet
      override def utx: UtxPool                                                                  = utxStorage
      override def broadcastTransaction(tx: Transaction): TracedResult[ValidationError, Boolean] =
        Await.result(
          transactionPublisher.validateAndBroadcast(tx, None),
          Duration.Inf
        ) // NOTE: Blocking call — async replacement requires significant refactor of tx publishing pipeline
      override def utxEvents: Observable[UtxEvent] = app.utxEvents

      override val transactionsApi: CommonTransactionsApi = CommonTransactionsApi(
        blockchainUpdater.bestLiquidSnapshot.map(Height(blockchainUpdater.height) -> _),
        rdb,
        blockchainUpdater,
        utxStorage,
        blockChallenger,
        tx => transactionPublisher.validateAndBroadcast(tx, None),
        loadBlockAt(rdb, blockchainUpdater)
      )
      override val blocksApi: CommonBlocksApi = CommonBlocksApi(
        settings.synchronizationSettings.maxRollback,
        blockchainUpdater,
        loadBlockMetaAt(rdb.db, blockchainUpdater),
        loadBlockInfoAt(rdb, blockchainUpdater)
      )
      override val accountsApi: CommonAccountsApi =
        CommonAccountsApi(() => blockchainUpdater.snapshotBlockchain, rdb, blockchainUpdater)
      override val assetsApi: CommonAssetsApi =
        CommonAssetsApi(() => blockchainUpdater.bestLiquidSnapshot.orEmpty, rdb.db, blockchainUpdater)
      override def generatorsApi: CommonGeneratorsApi =
        CommonGeneratorsApi(rdb, blockchainUpdater)
    }

    extensions = settings.extensions.map { extensionClassName =>
      val extensionClass = Class.forName(extensionClassName).asInstanceOf[Class[Extension]]
      val ctor           = extensionClass.getConstructor(classOf[Context])
      log.info(s"Enable extension: $extensionClassName")
      ctor.newInstance(extensionContext)
    }
    triggers ++= extensions.collect { case e: BlockchainUpdateTriggers => e }
    extensions.foreach(_.start())

    // Node start
    // After this point, node actually starts doing something
    appenderScheduler.execute(() => checkGenesis(settings, blockchainUpdater, miner))

    // Network server should be started only after all extensions initialized
    val networkServer =
      NetworkServerL1(
        settings,
        lastBlockInfo,
        historyReplier,
        peerDatabase,
        messageObserver,
        allChannels,
        establishedConnections
      )
    maybeNetworkServer = Some(networkServer)
    val timeoutSubject: ConcurrentSubject[Channel, Channel] = ConcurrentSubject.publish[Channel]

    val (syncWithChannelClosed, scoreStatsReporter) = RxScoreObserver(
      settings.synchronizationSettings.scoreTTL,
      1.second,
      blockchainUpdater.score,
      lastScore,
      messageObserver.blockchainScores,
      networkServer.closedChannels,
      timeoutSubject,
      scoreObserverScheduler
    )
    val (microblockDataWithSnapshot, mbSyncCacheSizes) = MicroBlockSynchronizer(
      settings.synchronizationSettings.microBlockSynchronizer,
      settings.enableLightMode,
      peerDatabase,
      lastBlockInfo.map(_.id),
      messageObserver.microblockInvs,
      messageObserver.microblockResponses,
      messageObserver.microblockSnapshots,
      microblockSynchronizerScheduler
    )

    messageObserver.endorseBlocks.foreach { case (ch, x) =>
      endorsementStorage.tryAdd(x) match {
        case Left(err)   => log.trace(s"Unexpected $x: $err")
        case Right(true) => allChannels.broadcast(x, Some(ch))
        case _           =>
      }
    }(using endorseBlockSynchronizerScheduler)

    val (newBlocksWithSnapshot, extLoaderState, _) = RxExtensionLoader(
      settings.synchronizationSettings.synchronizationTimeout,
      settings.synchronizationSettings.processedBlocksCacheTimeout,
      settings.enableLightMode,
      Coeval(blockchainUpdater.lastBlockIds(settings.synchronizationSettings.maxRollback)),
      peerDatabase,
      knownInvalidBlocks,
      messageObserver.blocks,
      messageObserver.signatures,
      messageObserver.blockSnapshots,
      syncWithChannelClosed,
      extensionLoaderScheduler,
      timeoutSubject
    ) { case (c, b) =>
      processFork(c, b).doOnFinish {
        case None    => Task.now(())
        case Some(e) => Task(stopOnAppendError.reportFailure(e))
      }
    }

    TransactionSynchronizer(
      settings.synchronizationSettings.utxSynchronizer,
      lastBlockInfo.map(_.id).distinctUntilChanged(using Eq.fromUniversalEquals),
      messageObserver.transactions,
      transactionPublisher
    )

    Observable(
      microblockDataWithSnapshot
        .mapEval(processMicroBlock.tupled),
      newBlocksWithSnapshot
        .mapEval(processBlock.tupled)
    ).mergeMap(identity)
      .onErrorHandle(stopOnAppendError.reportFailure)
      .subscribe()

    // API start
    if (settings.restAPISettings.enable) {

      val limitedScheduler =
        Schedulers.timeBoundedFixedPool(
          new HashedWheelTimer(),
          5.seconds,
          settings.restAPISettings.limitedPoolThreads,
          "rest-time-limited",
          reporter = log.trace("Uncaught exception in time limited pool", _)
        )
      val heavyRequestProcessorPoolThreads =
        settings.restAPISettings.heavyRequestProcessorPoolThreads.getOrElse((Runtime.getRuntime.availableProcessors() * 2).min(4))
      val heavyRequestExecutor = new ThreadPoolExecutor(
        heavyRequestProcessorPoolThreads,
        heavyRequestProcessorPoolThreads,
        0,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue[Runnable],
        new DefaultThreadFactory("rest-heavy-request-processor", true),
        { (r: Runnable, executor: ThreadPoolExecutor) =>
          log.error(s"$r has been rejected from $executor")
          throw new RejectedExecutionException
        }
      )

      val heavyRequestScheduler = Scheduler(
        if (settings.config.getBoolean("kamon.enable"))
          ExecutorInstrumentation.instrument(heavyRequestExecutor, "heavy-request-executor")
        else heavyRequestExecutor,
        ExecutionModel.BatchedExecution(100)
      )

      val serverRequestTimeout = FiniteDuration(settings.config.getDuration("pekko.http.server.request-timeout").getSeconds, TimeUnit.SECONDS)
      val routeTimeout         = new RouteTimeout(serverRequestTimeout)(using heavyRequestScheduler)

      val apiRoutes = Seq(
        new EthRpcRoute(blockchainUpdater, extensionContext.transactionsApi, time),
        NodeApiRoute(settings.restAPISettings, blockchainUpdater, () => shutdown()),
        BlocksApiRoute(settings.restAPISettings, extensionContext.blocksApi, time, routeTimeout),
        TransactionsApiRoute(
          settings.restAPISettings,
          extensionContext.transactionsApi,
          wallet,
          blockchainUpdater,
          () => blockchainUpdater.snapshotBlockchain,
          () => utxStorage.size,
          transactionPublisher,
          time,
          routeTimeout
        ),
        WalletApiRoute(settings.restAPISettings, wallet),
        UtilsApiRoute(
          time,
          settings.restAPISettings,
          settings.maxTxErrorLogSize,
          () => blockchainUpdater.estimator,
          limitedScheduler,
          blockchainUpdater
        ),
        PeersApiRoute(settings.restAPISettings, address => networkServer.connect(address), peerDatabase, establishedConnections),
        AddressApiRoute(
          settings.restAPISettings,
          wallet,
          blockchainUpdater,
          transactionPublisher,
          time,
          limitedScheduler,
          routeTimeout,
          extensionContext.accountsApi,
          settings.dbSettings.maxRollbackDepth
        ),
        GeneratorsApiRoute(settings.restAPISettings, blockchainUpdater, extensionContext.generatorsApi, time, routeTimeout),
        DebugApiRoute(
          settings,
          time,
          blockchainUpdater,
          wallet,
          extensionContext.accountsApi,
          extensionContext.transactionsApi,
          extensionContext.assetsApi,
          peerDatabase,
          establishedConnections,
          (id, returnTxs) => rollbackTask(id, returnTxs).map(_.map(_ => ())),
          utxStorage,
          miner,
          historyReplier,
          extLoaderState,
          mbSyncCacheSizes,
          scoreStatsReporter,
          configRoot,
          rocksDB,
          routeTimeout,
          heavyRequestScheduler
        ),
        AssetsApiRoute(
          settings.restAPISettings,
          serverRequestTimeout,
          wallet,
          blockchainUpdater,
          () => blockchainUpdater.snapshotBlockchain,
          time,
          extensionContext.accountsApi,
          extensionContext.assetsApi,
          settings.dbSettings.maxRollbackDepth,
          routeTimeout
        ),
        ActivationApiRoute(settings.restAPISettings, settings.featuresSettings, blockchainUpdater),
        LeaseApiRoute(
          settings.restAPISettings,
          wallet,
          blockchainUpdater,
          transactionPublisher,
          time,
          extensionContext.accountsApi,
          routeTimeout
        ),
        AliasApiRoute(
          settings.restAPISettings,
          extensionContext.transactionsApi,
          wallet,
          transactionPublisher,
          time,
          blockchainUpdater,
          routeTimeout
        ),
        RewardApiRoute(blockchainUpdater)
      )

      val httpService = CompositeHttpService(apiRoutes, settings.restAPISettings)
      val httpFuture  =
        Http().newServerAt(settings.restAPISettings.bindAddress, settings.restAPISettings.port).bindFlow(httpService.loggingCompositeRoute)
      serverBinding = Await.result(httpFuture, 20.seconds)
      serverBinding.whenTerminated.foreach(_ => heavyRequestScheduler.shutdown())
      log.info(s"REST API was bound on ${settings.restAPISettings.bindAddress}:${settings.restAPISettings.port}")
    }

    // on unexpected shutdown
    sys.addShutdownHook {
      timer.stop()
      shutdown()
    }
  }

  private val shutdownInProgress = new AtomicBoolean(false)

  def shutdown(): Unit =
    if (shutdownInProgress.compareAndSet(false, true)) {
      maybeUtx.foreach(_.close())

      log.info("Closing REST API")
      if (settings.restAPISettings.enable)
        Try(Await.ready(serverBinding.unbind(), 2.minutes)).failed.map(e => log.error("Failed to unbind REST API port", e))
      log.debug("Closing peer database")
      peerDatabase.close()

      Try(Await.result(actorSystem.terminate(), 2.minute)).failed.map(e => log.error("Failed to terminate actor system", e))
      log.debug("Node's actor system shutdown successful")

      blockchainUpdater.shutdown()

      maybeNetworkServer.foreach { network =>
        log.info("Stopping network services")
        network.shutdown()
      }
      messageObserver.shutdown()

      shutdownAndWait(appenderScheduler, "Appender", 5.minutes.some)

      log.info("Closing storage")
      rocksDB.close()
      rdb.close()

      // extensions should be shut down last, after all node functionality, to guarantee no data loss
      if (extensions.nonEmpty) {
        log.info(s"Shutting down extensions")
        Await.ready(Future.sequence(extensions.map(_.shutdown())), settings.extensionsShutdownTimeout)
      }

      time.close()
      log.info("Shutdown complete")
    }

  private def shutdownAndWait(scheduler: SchedulerService, name: String, timeout: Option[FiniteDuration], tryForce: Boolean = true): Unit = {
    log.debug(s"Shutting down $name")
    scheduler match {
      case es: ExecutorScheduler if tryForce => es.executor.shutdownNow()
      case s                                 => s.shutdown()
    }
    timeout.foreach { to =>
      val r = Await.result(scheduler.awaitTermination(to, global), 2 * to)
      if (r)
        log.info(s"$name was shutdown successfully")
      else
        log.warn(s"Failed to shutdown $name properly during timeout")
    }
  }
}

object Application extends ScorexLogging {
  def loadApplicationConfig(external: Option[File] = None): DCCSettings = {
    import com.decentralchain.settings.*

    val maybeExternalConfig = Try(external.map(f => ConfigFactory.parseFile(f.getAbsoluteFile, ConfigParseOptions.defaults().setAllowMissing(false))))
    val config              = loadConfig(maybeExternalConfig.getOrElse(None))

    // DO NOT LOG BEFORE THIS LINE, THIS PROPERTY IS USED IN logback.xml
    System.setProperty("dcc.directory", config.getString("dcc.directory"))
    if (config.hasPath("dcc.config.directory")) System.setProperty("dcc.config.directory", config.getString("dcc.config.directory"))

    maybeExternalConfig match {
      case Success(None) =>
        val currentBlockchainType = Try(ConfigFactory.defaultOverrides().getString("dcc.blockchain.type"))
          .orElse(Try(ConfigFactory.defaultOverrides().getString("dcc.defaults.blockchain.type")))
          .map(_.toUpperCase)
          .getOrElse("TESTNET")

        log.info(s"Config file not specified, default $currentBlockchainType config will be used")
      case Failure(exception) =>
        log.error(s"Couldn't read ${external.get.toPath.toAbsolutePath}", exception)
        forceStopApplication(Misconfiguration)
      case _ => // Pass
    }

    val settings = DCCSettings.fromRootConfig(config)

    // Initialize global var with actual address scheme
    AddressScheme.current = new AddressScheme {
      override val chainId: Byte = settings.blockchainSettings.addressSchemeCharacter.toByte
    }

    // IMPORTANT: to make use of default settings for histograms and timers, it's crucial to reconfigure Kamon with
    //            our merged config BEFORE initializing any metrics, including in settings-related companion objects
    if (config.getBoolean("kamon.enable")) {
      Kamon.init(config)
    } else {
      Kamon.reconfigure(config)
    }

    sys.addShutdownHook {
      Try(Await.result(Kamon.stop(), 30 seconds))
      Metrics.shutdown()
    }

    val DisabledHash = "H6nsiifwYKYEx6YzYD7woP1XCn72RVvx6tC1zjjLXqsu"
    if (settings.restAPISettings.enable && settings.restAPISettings.apiKeyHash == DisabledHash) {
      log.error(s"Usage of the default api key hash ($DisabledHash) is prohibited, please change it in the dcc.conf")
      forceStopApplication(Misconfiguration)
    }

    settings
  }

  private[decentralchain] def loadBlockAt(rdb: RDB, blockchainUpdater: CompleteBlockchainUpdater)(
      height: Height
  ): Option[(BlockMeta, Seq[(TxMeta, Transaction)])] =
    loadBlockInfoAt(rdb, blockchainUpdater)(height)

  private[decentralchain] def loadBlockInfoAt(rdb: RDB, blockchainUpdater: CompleteBlockchainUpdater)(
      height: Height
  ): Option[(BlockMeta, Seq[(TxMeta, Transaction)])] =
    loadBlockMetaAt(rdb.db, blockchainUpdater)(height).map { meta =>
      meta -> blockchainUpdater
        .liquidTransactions(meta.id)
        .getOrElse(database.loadTransactions(height, rdb))
    }

  private[decentralchain] def loadBlockMetaAt(db: RocksDB, blockchainUpdater: CompleteBlockchainUpdater)(height: Height): Option[BlockMeta] =
    blockchainUpdater.liquidBlockMeta
      .filter(_ => blockchainUpdater.height == height.toInt)
      .orElse(db.get(Keys.blockMetaAt(height)).flatMap(BlockMeta.fromPb))
      .map { blockMeta =>
        val rewardShares = BlockRewardCalculator.getSortedBlockRewardShares(height.toInt, blockMeta.header.generator.toAddress, blockchainUpdater)
        blockMeta.copy(
          rewardShares = rewardShares,
          reward = blockMeta.reward.map(_ * blockchainUpdater.blockRewardBoost(height))
        )
      }

  def main(args: Array[String]): Unit = {

    // prevents java from caching successful name resolutions, which is needed e.g. for proper NTP server rotation
    // http://stackoverflow.com/a/17219327
    System.setProperty("sun.net.inetaddr.ttl", "0")
    System.setProperty("sun.net.inetaddr.negative.ttl", "0")
    Security.setProperty("networkaddress.cache.ttl", "0")
    Security.setProperty("networkaddress.cache.negative.ttl", "0")

    args.headOption.getOrElse("") match {
      case "export"                 => Exporter.main(args.tail)
      case "import"                 => Importer.main(args.tail)
      case "explore"                => Explorer.main(args.tail)
      case "util"                   => UtilApp.main(args.tail)
      case "gengen"                 => GenesisBlockGenerator.main(args.tail)
      case "help" | "--help" | "-h" => println("Usage: dcc <config> | export | import | explore | util | gengen")
      case _                        => startNode(args.headOption)
    }
  }

  private def startNode(configFile: Option[String]): Unit = {
    import com.decentralchain.settings.Constants
    val settings = loadApplicationConfig(configFile.map(new File(_)))

    val log      = Logger(LoggerFactory.getLogger(getClass))
    val modeInfo = if (settings.enableLightMode) "in light mode" else "in full mode"
    log.info(s"Starting $modeInfo...")
    sys.addShutdownHook {
      SystemInformationReporter.report(settings.config)
    }

    val time = new NTP(settings.ntpServer)
    Metrics.start(settings.metrics, time)

    def dumpMinerConfig(): Unit = {
      import settings.minerSettings as miner
      import settings.synchronizationSettings.microBlockSynchronizer

      Metrics.write(
        Point
          .measurement("config")
          .addField("miner-micro-block-interval", miner.microBlockInterval.toMillis)
          .addField("miner-max-transactions-in-micro-block", miner.maxTransactionsInMicroBlock)
          .addField("miner-min-micro-block-age", miner.minMicroBlockAge.toMillis)
          .addField("mbs-wait-response-timeout", microBlockSynchronizer.waitResponseTimeout.toMillis)
      )
    }

    RootActorSystem.start("decentralchain", settings.config) { actorSystem =>
      dumpMinerConfig()
      log.info(s"${Constants.AgentName} Blockchain Id: ${settings.blockchainSettings.addressSchemeCharacter}")
      new Application(actorSystem, settings, settings.config.root(), time).run()
    }
  }
}
