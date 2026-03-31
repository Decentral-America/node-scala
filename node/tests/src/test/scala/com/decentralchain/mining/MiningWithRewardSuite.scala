package com.decentralchain.mining

import cats.effect.Resource
import com.typesafe.config.ConfigFactory
import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.consensus.PoSSelector
import com.decentralchain.database.{RDB, TestStorageFactory}
import com.decentralchain.db.DBCacheSettings
import com.decentralchain.features.{BlockchainFeature, BlockchainFeatures}
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.settings.*
import com.decentralchain.state.diffs.ENOUGH_AMT
import com.decentralchain.state.{BlockEndorser, Blockchain, BlockchainUpdaterImpl, EndorsementStorage, NG}
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.transfer.TransferTransaction
import com.decentralchain.transaction.{BlockchainUpdater, GenesisTransaction, Transaction}
import com.decentralchain.utx.UtxPoolImpl
import com.decentralchain.wallet.Wallet
import com.decentralchain.{TransactionGen, WithNewDBForEachTest}
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.compatible.Assertion
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future
import scala.concurrent.duration.*

class MiningWithRewardSuite extends AsyncFlatSpec with Matchers with WithNewDBForEachTest with TransactionGen with DBCacheSettings {
  import MiningWithRewardSuite.*

  behavior of "Miner with activated reward feature"

  it should "generate valid empty blocks of version 4" in {
    withEnv(Seq.empty) { case Env(_, account, miner, blockchain) =>
      val generateBlock = generateBlockTask(miner)(account)
      val oldBalance    = blockchain.balance(account.toAddress)
      val newBalance    = oldBalance + 2 * settings.blockchainSettings.rewardsSettings.initial
      for {
        _ <- generateBlock
        _ <- generateBlock
      } yield {
        blockchain.balance(account.toAddress) should be(newBalance)
        blockchain.height should be(3)
        blockchain.blockHeader(2).get.header.version should be(Block.RewardBlockVersion)
        blockchain.blockHeader(3).get.header.version should be(Block.RewardBlockVersion)
      }
    }
  }

  it should "generate valid empty block of version 4 after block of version 3" in {
    withEnv(Seq((ts, reference, _) => TestBlock.create(time = ts, ref = reference, txs = Nil, version = Block.NgBlockVersion).block)) {
      case Env(_, account, miner, blockchain) =>
        val generateBlock = generateBlockTask(miner)(account)
        val oldBalance    = blockchain.balance(account.toAddress)
        val newBalance    = oldBalance + settings.blockchainSettings.rewardsSettings.initial

        generateBlock.map { _ =>
          blockchain.balance(account.toAddress) should be(newBalance)
          blockchain.height should be(3)
        }
    }
  }

  it should "generate valid blocks with transactions of version 4" in {
    val bps: Seq[BlockProducer] = Seq((ts, reference, account) => {
      val recipient1 = createAccount.toAddress
      val recipient2 = createAccount.toAddress
      val tx1 = TransferTransaction
        .selfSigned(2.toByte, account, recipient1, Dcc, 10 * Constants.UnitsInDcc, Dcc, 400000, ByteStr.empty, ts)
        .explicitGet()
      val tx2 = TransferTransaction
        .selfSigned(2.toByte, account, recipient2, Dcc, 5 * Constants.UnitsInDcc, Dcc, 400000, ByteStr.empty, ts)
        .explicitGet()
      TestBlock.create(time = ts, ref = reference, txs = Seq(tx1, tx2), version = Block.NgBlockVersion).block
    })

    val txs: Seq[TransactionProducer] = Seq((ts, account) => {
      val recipient1 = createAccount.toAddress
      TransferTransaction
        .selfSigned(2.toByte, account, recipient1, Dcc, 10 * Constants.UnitsInDcc, Dcc, 400000, ByteStr.empty, ts)
        .explicitGet()
    })

    withEnv(bps, txs) { case Env(_, account, miner, blockchain) =>
      val generateBlock = generateBlockTask(miner)(account)
      val oldBalance    = blockchain.balance(account.toAddress)
      val newBalance    = oldBalance + settings.blockchainSettings.rewardsSettings.initial - 10 * Constants.UnitsInDcc

      generateBlock.map { _ =>
        blockchain.balance(account.toAddress) should be(newBalance)
        blockchain.height should be(3)
      }
    }

    // Test for empty key block with NG
    withEnv(bps, txs, settingsWithFeatures(BlockchainFeatures.NG, BlockchainFeatures.SmartAccounts)) { case Env(_, account, miner, _) =>
      val block = forgeBlock(miner)(account).explicitGet().newBlock
      Task(block.transactionData shouldBe empty)
    }
  }

  private def withEnv(bps: Seq[BlockProducer], txs: Seq[TransactionProducer] = Seq(), settings: DCCSettings = MiningWithRewardSuite.settings)(
      f: Env => Task[Assertion]
  ): Task[Assertion] =
    resources(settings).use { case (blockchainUpdater, _) =>
      for {
        _ <- Task.unit
        pos         = PoSSelector(blockchainUpdater, settings.synchronizationSettings.maxBaseTarget)
        utxPool     = new UtxPoolImpl(ntpTime, blockchainUpdater, settings.utxSettings, settings.maxTxErrorLogSize, settings.minerSettings.enable)
        scheduler   = Scheduler.singleThread("appender")
        allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
        wallet      = Wallet(WalletSettings(None, Some("123"), None))
        miner = new MinerImpl(
          allChannels,
          blockchainUpdater,
          settings,
          ntpTime,
          utxPool,
          BlockEndorser.Disabled,
          EndorsementStorage.Disabled,
          wallet,
          pos,
          scheduler,
          scheduler,
          Observable.empty
        )
        account      = createAccount
        ts           = ntpTime.correctedTime() - 60000
        genesisBlock = TestBlock.create(ts + 2, List(GenesisTransaction.create(account.toAddress, ENOUGH_AMT, ts + 1).explicitGet())).block
        _ <- Task {
          blockchainUpdater.processBlock(genesisBlock, genesisBlock.header.generationSignature, snapshot = None, generatorSet = Seq.empty)
        }
        blocks = bps.foldLeft {
          (ts + 1, Seq[Block](genesisBlock))
        } { case ((ts, chain), bp) =>
          (ts + 3, bp(ts + 3, chain.head.id(), account) +: chain)
        }._2
        added <- Task.traverse(blocks.reverse) { b =>
          Task(blockchainUpdater.processBlock(b, b.header.generationSignature, snapshot = None, generatorSet = Seq.empty))
        }
        _   = added.foreach(_.explicitGet())
        _   = txs.foreach(tx => utxPool.putIfNew(tx(ts + 6, account)).resultE.explicitGet())
        env = Env(blocks, account, miner, blockchainUpdater)
        r <- f(env)
        _ = scheduler.shutdown()
        _ = utxPool.close()
      } yield r
    }

  private def generateBlockTask(miner: MinerImpl)(account: KeyPair): Task[Unit] = miner.generateBlockTask(account, None)

  private def forgeBlock(miner: MinerImpl)(account: KeyPair): Either[String, ForgeAttemptResult.Success] = miner.forgeBlock(account).toEither

  private def resources(settings: DCCSettings): Resource[Task, (BlockchainUpdaterImpl, RDB)] =
    Resource
      .make {
        val (bcu, rdbWriter) = TestStorageFactory(settings, db, ntpTime, ignoreBlockchainUpdateTriggers)
        Task.now((bcu, rdbWriter, db))
      } { case (blockchainUpdater, rdbWriter, _) =>
        Task {
          blockchainUpdater.shutdown()
          rdbWriter.close()
        }
      }
      .map { case (blockchainUpdater, _, db) => (blockchainUpdater, db) }
}

object MiningWithRewardSuite {
  import TestFunctionalitySettings.Enabled
  import monix.execution.Scheduler.Implicits.global

  type BlockProducer       = (Long, ByteStr, KeyPair) => Block
  type TransactionProducer = (Long, KeyPair) => Transaction

  case class Env(blocks: Seq[Block], account: KeyPair, miner: MinerImpl, blockchain: Blockchain & BlockchainUpdater & NG)

  val settings: DCCSettings = {
    val commonSettings: DCCSettings = DCCSettings.fromRootConfig(loadConfig(ConfigFactory.load()))
    val minerSettings: MinerSettings =
      commonSettings.minerSettings.copy(quorum = 0, intervalAfterLastBlockThenGenerationIsAllowed = 1 hour)

    val functionalitySettings: FunctionalitySettings =
      Enabled
        .copy(preActivatedFeatures = Enabled.preActivatedFeatures + (BlockchainFeatures.BlockReward.id -> 0))
    val blockchainSettings: BlockchainSettings =
      commonSettings.blockchainSettings
        .copy(functionalitySettings = functionalitySettings)
        .copy(rewardsSettings = RewardsSettings.TESTNET)
    commonSettings.copy(minerSettings = minerSettings, blockchainSettings = blockchainSettings)
  }

  def settingsWithFeatures(features: BlockchainFeature*): DCCSettings = {
    val blockchainSettings = settings.blockchainSettings

    settings.copy(
      blockchainSettings = blockchainSettings.copy(
        functionalitySettings = blockchainSettings.functionalitySettings.copy(preActivatedFeatures = features.map(_.id -> 0).toMap)
      )
    )
  }

  def createAccount: KeyPair =
    Gen
      .containerOfN[Array, Byte](32, Arbitrary.arbitrary[Byte])
      .map(bs => KeyPair(bs))
      .sample
      .get

  private implicit def taskToFuture(task: Task[Assertion]): Future[Assertion] = task.runToFuture
}
