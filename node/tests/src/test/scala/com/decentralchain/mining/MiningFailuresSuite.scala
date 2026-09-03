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
import com.decentralchain.test.FlatSpec
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
import monix.reactive.Observable

class MiningFailuresSuite extends FlatSpec, WithNewDBForEachTest {
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

  private def generateBlockTask(miner: MinerImpl)(account: KeyPair): Task[Unit] =
    miner.generateBlockTask(account, None)
}
