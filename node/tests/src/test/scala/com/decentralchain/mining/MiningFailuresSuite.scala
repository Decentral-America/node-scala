package com.decentralchain.mining

import com.typesafe.config.ConfigFactory
import com.decentralchain.WithNewDBForEachTest
import com.decentralchain.account.{Address, KeyPair}
import com.decentralchain.api.BlockMeta
import com.decentralchain.block.{Block, BlockSnapshot, MicroBlock, MicroBlockSnapshot, SignedBlockHeader}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.PoSSelector
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.lang.ValidationError
import com.decentralchain.settings.*
import com.decentralchain.state.BlockchainUpdaterImpl.BlockApplyResult.Applied
import com.decentralchain.state.diffs.ENOUGH_AMT
import com.decentralchain.state.*
import com.decentralchain.test.{FlatSpec, TestSchedulerOps, TestTime}
import com.decentralchain.transaction.{BlockchainUpdater, DiscardedBlocks, LastBlockInfo, Transaction}
import com.decentralchain.transaction.TxValidationError.{BlockFromFuture, GenericError}
import com.decentralchain.utils.EmptyBlockchain
import com.decentralchain.utx.UtxPoolImpl
import com.decentralchain.wallet.Wallet
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicInt
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable

import scala.concurrent.duration.*

class MiningFailuresSuite extends FlatSpec, WithNewDBForEachTest, TestSchedulerOps {
  trait BlockchainUpdaterNG extends Blockchain with BlockchainUpdater with NG

  behavior of "Miner"

  it should "generate valid blocks ignoring time errors " in {
    @volatile var minedBlock: Block = null
    val genesis                     = TestBlock.create(System.currentTimeMillis(), Nil).block
    val blockchainUpdater           = new EmptyBlockchain with BlockchainUpdater with NG {
      override def height: Int = 1

      override def heightOf(blockId: ByteStr): Option[Int] = Some(1)

      override def hitSource(height: Int): Option[ByteStr] = Some(ByteStr(new Array[Byte](32)))

      override def blockHeader(height: Int): Option[SignedBlockHeader] = Some(SignedBlockHeader(genesis.header, genesis.signature))

      override def balanceSnapshots(address: Address, from: Int, to: Option[ByteStr]): Seq[BalanceSnapshot] =
        Seq(BalanceSnapshot(Height(1), ENOUGH_AMT, 0, 0, 0))

      override def bestLastBlockInfo(maxMicroblockTimestampMs: Long): Option[BlockMinerInfo] = Some(
        BlockMinerInfo(
          genesis.header.baseTarget,
          genesis.header.generationSignature,
          genesis.header.timestamp,
          genesis.id()
        )
      )

      override def isLastBlockId(id: ByteStr): Boolean = id == genesis.id() || Option(minedBlock).map(_.id()).contains(id)

      private val counter = AtomicInt(0)

      override def processBlock(
          block: Block,
          hitSource: ByteStr,
          snapshot: Option[BlockSnapshot],
          generatorSet: GeneratorSet,
          challengedHitSource: Option[ByteStr],
          verify: Boolean,
          txSignParCheck: Boolean
      ): Either[ValidationError, BlockchainUpdaterImpl.BlockApplyResult] =
        if (counter.getAndIncrement() >= 9) {
          minedBlock = block
          Right(Applied(Nil, 0, Seq.empty))
        } else
          Left(BlockFromFuture(100, 100))

      override def processMicroBlock(
          microBlock: MicroBlock,
          snapshot: Option[MicroBlockSnapshot],
          verify: Boolean
      ): Either[ValidationError, Block.BlockId] = ???

      override def computeNextReward: Option[Long] = Some(0)

      override def removeAfter(blockId: ByteStr): Either[ValidationError, DiscardedBlocks] = Right(Seq.empty)

      override def lastBlockInfo: Observable[LastBlockInfo] = Observable.empty

      override def referencedBlockchain(reference: ByteStr): Blockchain = this

      override def shutdown(): Unit = {}

      override def microBlock(id: ByteStr): Option[MicroBlock] = None

      override def microblockIds: Seq[Block.BlockId] = Seq.empty

      override def liquidBlock(id: ByteStr): Option[Block] = None

      override def liquidBlockSnapshot(id: ByteStr): Option[StateSnapshot] = None

      override def microBlockSnapshot(totalBlockId: ByteStr): Option[StateSnapshot] = None

      override def liquidTransactions(id: ByteStr): Option[Seq[(TxMeta, Transaction)]] = None

      override def liquidBlockMeta: Option[BlockMeta] = None

      override def bestLiquidSnapshot: Option[StateSnapshot] = None

      override def bestLiquidSnapshotAndFees: Option[(StateSnapshot, Long, Long)] = None

      override def snapshotBlockchain: SnapshotBlockchain = ???

      override def currentGeneratorSet: Option[GeneratorSet] = ???
    }

    val dccSettings = {
      val config = ConfigFactory
        .parseString("""
                       |dcc.miner {
                       |  quorum = 0
                       |  interval-after-last-block-then-generation-is-allowed = 0
                       |}
                       |
                       |dcc.features.supported=[2]
                       |""".stripMargin)
        .withFallback(ConfigFactory.load())

      DCCSettings.fromRootConfig(loadConfig(config))
    }

    val blockchainSettings = {
      val bs = dccSettings.blockchainSettings
      val fs = bs.functionalitySettings
      bs.copy(functionalitySettings = fs.copy(blockVersion3AfterHeight = 0, preActivatedFeatures = Map(2.toShort -> 0)))
    }

    val (miner, appenderScheduler) = {
      val scheduler   = Scheduler.singleThread("appender")
      val allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
      val wallet      = Wallet(WalletSettings(None, Some("123"), None))
      val utxPool     =
        new UtxPoolImpl(ntpTime, blockchainUpdater, dccSettings.utxSettings, dccSettings.maxTxErrorLogSize, dccSettings.minerSettings.enable)
      val pos = PoSSelector(blockchainUpdater, dccSettings.synchronizationSettings.maxBaseTarget)
      new MinerImpl(
        allChannels,
        blockchainUpdater,
        dccSettings.copy(blockchainSettings = blockchainSettings),
        ntpTime,
        utxPool,
        BlockEndorser.Disabled,
        EndorsementStorage.Disabled,
        wallet,
        pos,
        scheduler,
        scheduler,
        Observable.empty
      ) -> scheduler
    }

    val account       = accountGen.sample.get
    val generateBlock = generateBlockTask(miner)(account)
    generateBlock.runSyncUnsafe() shouldBe ((): Unit)
    minedBlock.header.featureVotes shouldBe empty
    appenderScheduler.shutdown()
  }

  // 2026-09-01 stall: a forge that succeeds followed by an append that fails (e.g. a transient
  // InvalidStateHash mismatch) must NOT permanently silence the miner. Before the fix, appendTask's
  // Left(err) branch did Task.raiseError, which propagated out of generateBlockTask to
  // scheduleMining's onErrorHandle -- a combinator that only logs and then completes the task, with
  // nothing left to ever re-trigger mining for this account again (scheduleMining is otherwise only
  // re-triggered by a block append or state-change event, which on a network where THIS node is the
  // sole forger may never happen). This test proves the OLD shape (a single Left(err) -> exception
  // escaping generateBlockTask, exactly the pre-fix RuntimeException) and the NEW shape (the SAME
  // task recovering by retrying, exactly like the pre-existing BlockFromFuture retry proven by the
  // test above) side by side, using the same stub-Blockchain harness.
  it should "reschedule and eventually succeed after a transient append failure, instead of dying" in {
    @volatile var minedBlock: Block = null
    val genesis                     = TestBlock.create(System.currentTimeMillis(), Nil).block
    val failuresBeforeSuccess       = 3
    val blockchainUpdater           = new EmptyBlockchain with BlockchainUpdater with NG {
      override def height: Int = 1

      override def heightOf(blockId: ByteStr): Option[Int] = Some(1)

      override def hitSource(height: Int): Option[ByteStr] = Some(ByteStr(new Array[Byte](32)))

      override def blockHeader(height: Int): Option[SignedBlockHeader] = Some(SignedBlockHeader(genesis.header, genesis.signature))

      override def balanceSnapshots(address: Address, from: Int, to: Option[ByteStr]): Seq[BalanceSnapshot] =
        Seq(BalanceSnapshot(Height(1), ENOUGH_AMT, 0, 0, 0))

      override def bestLastBlockInfo(maxMicroblockTimestampMs: Long): Option[BlockMinerInfo] = Some(
        BlockMinerInfo(
          genesis.header.baseTarget,
          genesis.header.generationSignature,
          genesis.header.timestamp,
          genesis.id()
        )
      )

      override def isLastBlockId(id: ByteStr): Boolean = id == genesis.id() || Option(minedBlock).map(_.id()).contains(id)

      private val counter = AtomicInt(0)

      override def processBlock(
          block: Block,
          hitSource: ByteStr,
          snapshot: Option[BlockSnapshot],
          generatorSet: GeneratorSet,
          challengedHitSource: Option[ByteStr],
          verify: Boolean,
          txSignParCheck: Boolean
      ): Either[ValidationError, BlockchainUpdaterImpl.BlockApplyResult] =
        if (counter.getAndIncrement() >= failuresBeforeSuccess) {
          minedBlock = block
          Right(Applied(Nil, 0, Seq.empty))
        } else
          // A stand-in for a real, transient append failure (e.g. InvalidStateHash at the moment of
          // the 2026-09-01 incident) -- what matters for this test is that it is NOT BlockFromFuture
          // (that retry path already exists and is proven by the test above), so it exercises the
          // Left(err) branch in Miner.scala's appendTask that this fix changes.
          Left(GenericError("Simulated transient append failure (e.g. InvalidStateHash mismatch)"))

      override def processMicroBlock(
          microBlock: MicroBlock,
          snapshot: Option[MicroBlockSnapshot],
          verify: Boolean
      ): Either[ValidationError, Block.BlockId] = ???

      override def computeNextReward: Option[Long] = Some(0)

      override def removeAfter(blockId: ByteStr): Either[ValidationError, DiscardedBlocks] = Right(Seq.empty)

      override def lastBlockInfo: Observable[LastBlockInfo] = Observable.empty

      override def referencedBlockchain(reference: ByteStr): Blockchain = this

      override def shutdown(): Unit = {}

      override def microBlock(id: ByteStr): Option[MicroBlock] = None

      override def microblockIds: Seq[Block.BlockId] = Seq.empty

      override def liquidBlock(id: ByteStr): Option[Block] = None

      override def liquidBlockSnapshot(id: ByteStr): Option[StateSnapshot] = None

      override def microBlockSnapshot(totalBlockId: ByteStr): Option[StateSnapshot] = None

      override def liquidTransactions(id: ByteStr): Option[Seq[(TxMeta, Transaction)]] = None

      override def liquidBlockMeta: Option[BlockMeta] = None

      override def bestLiquidSnapshot: Option[StateSnapshot] = None

      override def bestLiquidSnapshotAndFees: Option[(StateSnapshot, Long, Long)] = None

      override def snapshotBlockchain: SnapshotBlockchain = ???

      override def currentGeneratorSet: Option[GeneratorSet] = ???
    }

    val dccSettings = {
      val config = ConfigFactory
        .parseString("""
                       |dcc.miner {
                       |  quorum = 0
                       |  interval-after-last-block-then-generation-is-allowed = 0
                       |  no-quorum-mining-delay = 10ms
                       |}
                       |
                       |dcc.features.supported=[2]
                       |""".stripMargin)
        .withFallback(ConfigFactory.load())

      DCCSettings.fromRootConfig(loadConfig(config))
    }

    val blockchainSettings = {
      val bs = dccSettings.blockchainSettings
      val fs = bs.functionalitySettings
      bs.copy(functionalitySettings = fs.copy(blockVersion3AfterHeight = 0, preActivatedFeatures = Map(2.toShort -> 0)))
    }

    val (miner, appenderScheduler) = {
      val scheduler   = Scheduler.singleThread("appender")
      val allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
      val wallet      = Wallet(WalletSettings(None, Some("123"), None))
      val utxPool     =
        new UtxPoolImpl(ntpTime, blockchainUpdater, dccSettings.utxSettings, dccSettings.maxTxErrorLogSize, dccSettings.minerSettings.enable)
      val pos = PoSSelector(blockchainUpdater, dccSettings.synchronizationSettings.maxBaseTarget)
      new MinerImpl(
        allChannels,
        blockchainUpdater,
        dccSettings.copy(blockchainSettings = blockchainSettings),
        ntpTime,
        utxPool,
        BlockEndorser.Disabled,
        EndorsementStorage.Disabled,
        wallet,
        pos,
        scheduler,
        scheduler,
        Observable.empty
      ) -> scheduler
    }

    val account       = accountGen.sample.get
    val generateBlock = generateBlockTask(miner)(account)

    // OLD behavior (pre-fix): appendTask's Left(err) branch did Task.raiseError, so this single Task
    // (which is exactly what scheduleMining wraps in onErrorHandle in production) would fail on the
    // very FIRST append attempt, well before failuresBeforeSuccess retries ever happened -- proving
    // the miner had no way to recover on its own. NEW behavior (post-fix): the same task retries
    // internally (delayed by no-quorum-mining-delay, here shortened to 10ms so the test stays fast)
    // and keeps going until processBlock finally returns Right(Applied(...)).
    generateBlock.runSyncUnsafe() shouldBe ((): Unit)
    minedBlock should not be null
    minedBlock.header.featureVotes shouldBe empty
    appenderScheduler.shutdown()
  }

  // Review finding on d477af8982: appendTask's failure branches (Left(err), Right(Ignored)) now
  // recurse into a delayed retry (Task.defer(generateBlockTask(...)).delayExecution(...)) instead of
  // Task.raiseError, but the ENTIRE flatMap dispatching on BlockAppender's result used to be wrapped
  // in .uncancelable. In Monix, .uncancelable masks cancellation for the whole dynamic extent of
  // everything chained inside it, including a recursive retry value and every subsequent re-entry
  // into appendTask. So once a forge attempt failed once, the resulting retry chain became
  // permanently immune to cancellation for as long as it kept failing -- when scheduleMining() is
  // called again (e.g. a legitimate external block arrives) and reassigns scheduledAttempts, the
  // cancel of the OLD CompositeCancelable is a no-op against the stale uncancelable chain, so the old
  // chain keeps running concurrently with the new one for the same account.
  //
  // This test proves the fix: with a stub Blockchain whose processBlock ALWAYS fails (never
  // BlockFromFuture, so it always takes the delayed-retry branch under test), drive the miner through
  // the REAL scheduleMining()/scheduledAttempts path (not generateBlockTask directly, which bypasses
  // scheduledAttempts entirely and cannot observe this hazard) using TestSchedulers for deterministic
  // virtual time. Call scheduleMining() once, run tasks until the first forge attempt fails and enters
  // its delayed retry, then call scheduleMining() again (simulating the external block arrival) and
  // keep draining individual scheduled tasks, tracking the maximum number pending at once across both
  // schedulers.
  //
  // If the stale chain is not actually cancelled (the pre-fix hazard), BOTH the stale and the fresh
  // chain are independently waiting on their own pending task at the same time, so that max observably
  // hits 2. If cancellation reaches the stale chain (post-fix), only the fresh chain is ever alive, so
  // at most 1 task is ever pending -- a direct, deterministic proof that the stale chain was actually
  // cancelled, not merely outraced.
  it should "cancel a stale delayed-retry chain when scheduleMining is called again, instead of racing it" in {
    // A real Time (like ntpTime, used by the other tests in this suite) drifts against the
    // TestSchedulers' virtual clock -- forgeBlock's "Block time is from the future" check
    // (maxTimeDrift = 100ms) is sensitive to that drift and can push every attempt into the
    // ForgeAttemptResult.TemporaryFailure retry path (never reaching appendTask/BlockAppender at
    // all, the path this test actually needs to exercise). TestTime keeps the clock fully
    // deterministic and under the test's control, matching MinerWithFinalitySuite's approach.
    val testTime         = TestTime(System.currentTimeMillis() - 1.hour.toMillis)
    val genesis           = TestBlock.create(testTime.correctedTime(), Nil).block
    val processBlockCalls = AtomicInt(0)
    val blockchainUpdater = new EmptyBlockchain with BlockchainUpdater with NG {
      override def height: Int = 1

      override def heightOf(blockId: ByteStr): Option[Int] = Some(1)

      override def hitSource(height: Int): Option[ByteStr] = Some(ByteStr(new Array[Byte](32)))

      override def blockHeader(height: Int): Option[SignedBlockHeader] = Some(SignedBlockHeader(genesis.header, genesis.signature))

      override def balanceSnapshots(address: Address, from: Int, to: Option[ByteStr]): Seq[BalanceSnapshot] =
        Seq(BalanceSnapshot(Height(1), ENOUGH_AMT, 0, 0, 0))

      override def bestLastBlockInfo(maxMicroblockTimestampMs: Long): Option[BlockMinerInfo] = Some(
        BlockMinerInfo(
          genesis.header.baseTarget,
          genesis.header.generationSignature,
          genesis.header.timestamp,
          genesis.id()
        )
      )

      override def isLastBlockId(id: ByteStr): Boolean = id == genesis.id()

      // Always fails with a non-BlockFromFuture error, so appendTask's Left(err) delayed-retry
      // branch under test fires every time, forever -- the retry chain never terminates on its own,
      // which is exactly the shape that needs cancellation to ever stop.
      override def processBlock(
          block: Block,
          hitSource: ByteStr,
          snapshot: Option[BlockSnapshot],
          generatorSet: GeneratorSet,
          challengedHitSource: Option[ByteStr],
          verify: Boolean,
          txSignParCheck: Boolean
      ): Either[ValidationError, BlockchainUpdaterImpl.BlockApplyResult] = {
        processBlockCalls.increment()
        Left(GenericError("Simulated permanent append failure, forces indefinite delayed retries"))
      }

      override def processMicroBlock(
          microBlock: MicroBlock,
          snapshot: Option[MicroBlockSnapshot],
          verify: Boolean
      ): Either[ValidationError, Block.BlockId] = ???

      override def computeNextReward: Option[Long] = Some(0)

      override def removeAfter(blockId: ByteStr): Either[ValidationError, DiscardedBlocks] = Right(Seq.empty)

      override def lastBlockInfo: Observable[LastBlockInfo] = Observable.empty

      override def referencedBlockchain(reference: ByteStr): Blockchain = this

      override def shutdown(): Unit = {}

      override def microBlock(id: ByteStr): Option[MicroBlock] = None

      override def microblockIds: Seq[Block.BlockId] = Seq.empty

      override def liquidBlock(id: ByteStr): Option[Block] = None

      override def liquidBlockSnapshot(id: ByteStr): Option[StateSnapshot] = None

      override def microBlockSnapshot(totalBlockId: ByteStr): Option[StateSnapshot] = None

      override def liquidTransactions(id: ByteStr): Option[Seq[(TxMeta, Transaction)]] = None

      override def liquidBlockMeta: Option[BlockMeta] = None

      override def bestLiquidSnapshot: Option[StateSnapshot] = None

      override def bestLiquidSnapshotAndFees: Option[(StateSnapshot, Long, Long)] = None

      override def snapshotBlockchain: SnapshotBlockchain = ???

      override def currentGeneratorSet: Option[GeneratorSet] = ???
    }

    val noQuorumMiningDelay = 100.millis

    val dccSettings = {
      val config = ConfigFactory
        .parseString(s"""
                       |dcc.miner {
                       |  quorum = 0
                       |  interval-after-last-block-then-generation-is-allowed = 0
                       |  no-quorum-mining-delay = ${noQuorumMiningDelay.toMillis}ms
                       |}
                       |
                       |dcc.features.supported=[2]
                       |""".stripMargin)
        .withFallback(ConfigFactory.load())

      DCCSettings.fromRootConfig(loadConfig(config))
    }

    val blockchainSettings = {
      val bs = dccSettings.blockchainSettings
      val fs = bs.functionalitySettings
      bs.copy(functionalitySettings = fs.copy(blockVersion3AfterHeight = 0, preActivatedFeatures = Map(2.toShort -> 0)))
    }

    val minerScheduler    = TestScheduler()
    val appenderScheduler = TestScheduler()
    val allChannels       = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    val wallet            = Wallet(WalletSettings(None, Some("123"), None))
    wallet.generateNewAccount()
    val utxPool = new UtxPoolImpl(testTime, blockchainUpdater, dccSettings.utxSettings, dccSettings.maxTxErrorLogSize, dccSettings.minerSettings.enable)
    val pos     = PoSSelector(blockchainUpdater, dccSettings.synchronizationSettings.maxBaseTarget)
    val miner   = new MinerImpl(
      allChannels,
      blockchainUpdater,
      dccSettings.copy(blockchainSettings = blockchainSettings),
      testTime,
      utxPool,
      BlockEndorser.Disabled,
      EndorsementStorage.Disabled,
      wallet,
      pos,
      minerScheduler,
      appenderScheduler,
      Observable.empty
    )

    // Fires exactly the single earliest-scheduled pending task across BOTH schedulers (whichever of
    // the two holds it), advancing TestTime by the same virtual delta first. forgeBlock's "block time
    // is from the future" / age checks compare against testTime.correctedTime(), which is otherwise
    // frozen -- it must move in lockstep with whichever scheduler's clock is about to advance, or
    // every retry falls into the (unrelated) TemporaryFailure path instead of the Left(err)
    // append-failure path this test targets. Driving one task at a time (rather than jumping by a
    // duration and draining whatever that unblocks) is what makes it possible to stop counting
    // processBlock calls at an exact, deterministic round boundary.
    def runOneTask(): Boolean = {
      val nextAppender = appenderScheduler.state.tasks.headOption.map(_.runsAt)
      val nextMiner    = minerScheduler.state.tasks.headOption.map(_.runsAt)
      (nextAppender, nextMiner) match {
        case (None, None) => false
        case _            =>
          val targetTime = List(nextAppender, nextMiner).flatten.min
          val delta      = (targetTime - appenderScheduler.state.clock).max(0.nanos)
          testTime.advance(FiniteDuration(delta.toNanos, NANOSECONDS))
          if (nextAppender.contains(targetTime)) appenderScheduler.tickNext("appender", failIfNoTasks = false)
          else minerScheduler.tickNext("miner", failIfNoTasks = false)
          true
      }
    }

    // First scheduleMining() call: generateBlockTask's chain is launched on appenderScheduler
    // (runAsyncLogErr(using appenderScheduler) in scheduleMining), hops to minerScheduler just for the
    // Task(forgeBlock(account)).executeOn(minerScheduler) step, then the append (and its failure) runs
    // back on appenderScheduler, landing in the Left(err) branch's
    // Task.defer(...).delayExecution(noQuorumMiningDelay) -- a pending, not-yet-fired retry task.
    // Run tasks one at a time and stop the instant the first processBlock call lands, so this baseline
    // is pinned at exactly 1 -- none of noQuorumMiningDelay's own retry rounds get a chance to also
    // fire in the same step.
    miner.scheduleMining(None, cancelMicroBlockMining = false)
    var stepsLeft = 20
    while (processBlockCalls.get() == 0 && stepsLeft > 0 && runOneTask()) stepsLeft -= 1

    val callsAfterFirstFailure = processBlockCalls.get()
    callsAfterFirstFailure shouldBe 1

    // Simulate an external block arriving: something calls scheduleMining() again for the same
    // account while the OLD chain's delayed retry is still pending. This reassigns scheduledAttempts,
    // which cancels whatever CompositeCancelable was previously running.
    miner.scheduleMining(None, cancelMicroBlockMining = false)

    // Direct proof of the fix: a single live retry chain has AT MOST one task pending at a time
    // across both schedulers -- it forges (one task), hops scheduler to append (another task), then
    // waits out noQuorumMiningDelay before its next forge (one more task) -- never two in flight at
    // once. If the OLD chain survived cancellation (the pre-fix hazard), it and the NEW chain would
    // each independently be waiting on their own pending task at the same time, so the combined
    // pending-task count would observably hit 2 at some point while draining. Recording the maximum
    // combined pending-task count seen while running a generous number of individual tasks (enough
    // for several retry rounds) distinguishes "one chain alive" from "two chains racing" directly,
    // rather than inferring it indirectly from a processBlock call count.
    var maxPendingTasksSeen = 0
    val tasksToRun          = 40
    for (_ <- 1 to tasksToRun) {
      val pending = minerScheduler.state.tasks.size + appenderScheduler.state.tasks.size
      if (pending > maxPendingTasksSeen) maxPendingTasksSeen = pending
      runOneTask()
    }

    val callsFromNew = processBlockCalls.get() - callsAfterFirstFailure

    // Post-fix: only the fresh chain is alive, so at most 1 task is ever pending at once, and it
    // keeps making progress (at least one more processBlock call beyond the stale chain's single
    // recorded failure). Pre-fix, the stale chain's cancellation would be a no-op, so 2 tasks would
    // be pending simultaneously as soon as the fresh chain's first forge attempt is also scheduled.
    maxPendingTasksSeen shouldBe 1
    callsFromNew should be >= 1
  }

  private def generateBlockTask(miner: MinerImpl)(account: KeyPair): Task[Unit] =
    miner.generateBlockTask(account, None)
}
