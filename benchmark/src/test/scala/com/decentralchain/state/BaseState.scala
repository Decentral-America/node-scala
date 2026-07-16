package com.decentralchain.state

import com.typesafe.config.ConfigFactory
import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.database.{RDB, RocksDBWriter}
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.mining.MiningConstraint
import com.decentralchain.settings.{FunctionalitySettings, DCCSettings, loadConfig}
import com.decentralchain.state.diffs.BlockDiffer
import com.decentralchain.state.utils.TestRocksDB
import com.decentralchain.transaction.{GenesisTransaction, Transaction}
import org.openjdk.jmh.annotations.{Setup, TearDown}
import org.scalacheck.{Arbitrary, Gen}

import java.io.File
import java.nio.file.Files

trait BaseState {
  import BaseState.*

  val benchSettings: Settings  = Settings.fromConfig(ConfigFactory.load())
  val dccSettings: DCCSettings = {
    val config = loadConfig(ConfigFactory.parseFile(new File(benchSettings.networkConfigFile)))
    DCCSettings.fromRootConfig(config)
  }
  private val fsSettings: FunctionalitySettings = updateFunctionalitySettings(FunctionalitySettings.TESTNET)
  private val rdb: RDB                          = {
    val dir = Files.createTempDirectory("state-synthetic").toAbsolutePath.toString
    RDB.open(dccSettings.dbSettings.copy(directory = dir))
  }

  val state: RocksDBWriter = TestRocksDB.withFunctionalitySettings(rdb, fsSettings)

  private var _richAccount: KeyPair = scala.compiletime.uninitialized
  def richAccount: KeyPair          = _richAccount

  private var _lastBlock: Block = scala.compiletime.uninitialized
  def lastBlock: Block          = _lastBlock

  protected def dcc(n: Float): Long      = (n * 100000000L).toLong
  protected val accountGen: Gen[KeyPair] =
    Gen.containerOfN[Array, Byte](32, Arbitrary.arbitrary[Byte]).map(seed => KeyPair(seed))

  protected def updateFunctionalitySettings(base: FunctionalitySettings): FunctionalitySettings = base

  protected def txGenP(sender: KeyPair, ts: Long): Gen[Transaction]

  private def genBlock(base: Block, sender: KeyPair): Gen[Block] =
    for {
      transferTxs <- Gen.sequence[Vector[Transaction], Transaction]((1 to TxsInBlock).map { i =>
        txGenP(sender, base.header.timestamp + i)
      })
    } yield TestBlock
      .create(
        time = transferTxs.last.timestamp,
        ref = base.id(),
        txs = transferTxs
      )
      .block

  private val initGen: Gen[(KeyPair, Block)] = for {
    rich <- accountGen
  } yield {
    val genesisTx = GenesisTransaction.create(rich.toAddress, dcc(100000000L), System.currentTimeMillis() - 10000).explicitGet()
    (rich, TestBlock.create(time = genesisTx.timestamp, Seq(genesisTx)).block)
  }

  protected def nextBlock(txs: Seq[Transaction]): Block = TestBlock
    .create(
      time = txs.last.timestamp,
      ref = lastBlock.id(),
      txs = txs
    )
    .block

  private def append(prev: Option[Block], next: Block): Unit = {
    val differResult =
      BlockDiffer
        .fromBlock(state, prev, next, None, MiningConstraint.Unlimited, next.header.generationSignature)
        .explicitGet()

    state.append(
      differResult.snapshot,
      carryFee = 0,
      totalFee = 0,
      reward = None,
      next.header.generationSignature,
      differResult.computedStateHash,
      next,
      newFinalizedHeight = GenesisBlockHeight,
      generatorSet = Seq.empty
    )
  }

  def applyBlock(b: Block): Unit = {
    append(Some(lastBlock), b)
    _lastBlock = b
  }

  def genAndApplyNextBlock(): Unit = {
    val b = genBlock(lastBlock, richAccount).sample.get
    applyBlock(b)
  }

  @Setup
  def init(): Unit = {
    val (richAccount, genesisBlock) = initGen.sample.get
    _richAccount = richAccount

    append(None, genesisBlock)
    _lastBlock = genesisBlock
  }

  @TearDown
  def close(): Unit = {
    state.close()
    rdb.close()
  }
}

object BaseState {
  private val TxsInBlock = 5000
}
