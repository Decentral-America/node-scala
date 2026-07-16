package com.decentralchain.state

import com.typesafe.config.ConfigFactory
import com.decentralchain.account.*
import com.decentralchain.api.BlockMeta
import com.decentralchain.api.common.CommonBlocksApi
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.database
import com.decentralchain.database.{DBExt, Keys, RDB, RocksDBWriter}
import com.decentralchain.settings.{DCCSettings, loadConfig}
import com.decentralchain.state.RocksDBWriterBenchmark.*
import com.decentralchain.transaction.Transaction
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.io.File
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import scala.io.Codec
import scala.util.Using

/** Tests over real database. How to test:
  *   1. Download a database 2. Import it:
  *      https://github.com/Decentral-America/node-scala/wiki/Export-and-import-of-the-blockchain#import-blocks-from-the-binary-file 3. Run ExtractInfo to
  *      collect queries for tests 4. Make Caches.MaxSize = 1 5. Run this test
  */
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 100)
class RocksDBWriterBenchmark {
  @Benchmark
  def readFullBlock_test(st: BlocksByIdSt, bh: Blackhole): Unit = {
    bh.consume(st.blockById(st.allBlocks.random).get)
  }

  @Benchmark
  def readBlockHeader_test(st: BlocksByHeightSt, bh: Blackhole): Unit = {
    bh.consume(st.db.blockHeader(st.allBlocks.random).get)
  }

  @Benchmark
  def transactionById_test(st: TransactionByIdSt, bh: Blackhole): Unit = {
    bh.consume(st.db.transactionInfo(st.allTxs.random).get)
  }
}

object RocksDBWriterBenchmark {

  @State(Scope.Benchmark)
  class TransactionByIdSt extends BaseSt {
    val allTxs: Vector[ByteStr] = load(benchSettings.restTxsFile)(x => ByteStr(Base58.tryDecodeWithLimit(x).get))
  }

  @State(Scope.Benchmark)
  class TransactionByAddressSt extends BaseSt {
    val txsAddresses: Vector[Address] = load(???)(x => Address.fromString(x).explicitGet())
  }

  @State(Scope.Benchmark)
  class BlocksByIdSt extends BaseSt {
    val allBlocks: Vector[ByteStr] = load(benchSettings.blocksFile)(x => ByteStr(Base58.tryDecodeWithLimit(x).get))
  }

  @State(Scope.Benchmark)
  class BlocksByHeightSt extends BaseSt {
    val allBlocks: Vector[Int] = load(benchSettings.blocksFile)(_.toInt)
  }

  @State(Scope.Benchmark)
  class BaseSt {
    protected val benchSettings: Settings = Settings.fromConfig(ConfigFactory.load())
    private val dccSettings: DCCSettings  = {
      val config = loadConfig(ConfigFactory.parseFile(new File(benchSettings.networkConfigFile)))
      DCCSettings.fromRootConfig(config)
    }

    AddressScheme.current = new AddressScheme {
      override val chainId: Byte = dccSettings.blockchainSettings.addressSchemeCharacter.toByte
    }

    private val rawDB: RDB = {
      val dir = new File(dccSettings.dbSettings.directory)
      if (!dir.isDirectory) throw new IllegalArgumentException(s"Can't find directory at '${dccSettings.dbSettings.directory}'")
      RDB.open(dccSettings.dbSettings)
    }

    val db = RocksDBWriter(rawDB, dccSettings.blockchainSettings, dccSettings.dbSettings, dccSettings.enableLightMode)

    def loadBlockInfoAt(height: Height): Option[(BlockMeta, Seq[(TxMeta, Transaction)])] =
      loadBlockMetaAt(height).map { meta =>
        meta -> database.loadTransactions(height, rawDB)
      }

    def loadBlockMetaAt(height: Height): Option[BlockMeta] = rawDB.db.get(Keys.blockMetaAt(height)).flatMap(BlockMeta.fromPb)

    val cba = CommonBlocksApi(dccSettings.synchronizationSettings.maxRollback, db, loadBlockMetaAt, loadBlockInfoAt)

    def blockById(id: ByteStr): Option[(BlockMeta, Seq[(TxMeta, Transaction)])] = cba.block(id)

    @TearDown
    def close(): Unit = {
      db.close()
      rawDB.close()
    }

    protected def load[T](absolutePath: String)(f: String => T): Vector[T] = {
      Using.resource(scala.io.Source.fromFile(absolutePath)(using Codec.UTF8))(_.getLines().map(f).toVector)
    }
  }

  implicit class VectorOps[T](self: Vector[T]) {
    def random: T = self(ThreadLocalRandom.current().nextInt(self.size))
  }
}
