package com.decentralchain.state

import cats.Id
import com.typesafe.config.ConfigFactory
import com.decentralchain.account.{AddressOrAlias, AddressScheme, Alias}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.database.{RDB, RocksDBWriter}
import com.decentralchain.lang.directives.DirectiveSet
import com.decentralchain.lang.v1.traits.Environment
import com.decentralchain.lang.v1.traits.domain.Recipient
import com.decentralchain.settings.{DCCSettings, loadConfig}
import com.decentralchain.state.DCCEnvironmentBenchmark.*
import com.decentralchain.state.bench.DataTestData
import com.decentralchain.transaction.smart.DCCEnvironment
import monix.eval.Coeval
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import scodec.bits.BitVector

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
@Measurement(iterations = 10)
class DCCEnvironmentBenchmark {

  @Benchmark
  def resolveAddress_test(st: ResolveAddressSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.resolveAlias(st.aliases.random))
  }

  @Benchmark
  def transactionById_test(st: TransactionByIdSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.transactionById(st.allTxs.random))
  }

  @Benchmark
  def transactionHeightById_test(st: TransactionByIdSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.transactionById(st.allTxs.random))
  }

  @Benchmark
  def accountBalanceOf_dcc_test(st: AccountBalanceOfDccSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.accountBalanceOf(Recipient.Address(ByteStr(st.accounts.random)), None))
  }

  @Benchmark
  def accountBalanceOf_asset_test(st: AccountBalanceOfAssetSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.accountBalanceOf(Recipient.Address(ByteStr(st.accounts.random)), Some(st.assets.random)))
  }

  @Benchmark
  def data_test(st: DataSt, bh: Blackhole): Unit = {
    val x = st.data.random
    bh.consume(st.environment.data(Recipient.Address(x.addr), x.key, x.dataType))
  }

  @Benchmark
  def transferTransactionFromProto(st: TransferFromProtoSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.transferTransactionFromProto(st.transferTxBytes))
  }

}

object DCCEnvironmentBenchmark {

  @State(Scope.Benchmark)
  class ResolveAddressSt extends BaseSt {
    val aliases: Vector[String] = load(benchSettings.aliasesFile)(x => Alias.fromString(x).explicitGet().name)
  }

  @State(Scope.Benchmark)
  class TransactionByIdSt extends BaseSt {
    val allTxs: Vector[Array[Byte]] = load(benchSettings.restTxsFile)(x => Base58.tryDecodeWithLimit(x).get)
  }

  @State(Scope.Benchmark)
  class TransactionHeightByIdSt extends TransactionByIdSt

  @State(Scope.Benchmark)
  class AccountBalanceOfDccSt extends BaseSt {
    val accounts: Vector[Array[Byte]] = load(benchSettings.accountsFile)(x => AddressOrAlias.fromString(x).explicitGet().bytes)
  }

  @State(Scope.Benchmark)
  class AccountBalanceOfAssetSt extends AccountBalanceOfDccSt {
    val assets: Vector[Array[Byte]] = load(benchSettings.assetsFile)(x => Base58.tryDecodeWithLimit(x).get)
  }

  @State(Scope.Benchmark)
  class DataSt extends BaseSt {
    val data: Vector[DataTestData] = load(benchSettings.dataFile) { line =>
      DataTestData.codec.decode(BitVector.fromBase64(line).get).require.value
    }
  }

  @State(Scope.Benchmark)
  class TransferFromProtoSt extends BaseSt {
    val transferTxBytesBase58: String =
      "3nec7yqpNKXGsmPw7eTRUi8WPEaRmHfnHHSc8NZJggjsMp7SKGyLNnFk5NmZLTHQXaXqUEiSWqfPaXznZ3Drh" +
        "8siJpCzS9AtSTUKS7yYBFUrj4jeQZM5axqbJHeYgLoDNwCXUcfwNdQ2XfQceSoYx6cEDc4MrXsA4GGnXWvoPD" +
        "TKrX8EQCtcwJ8QbErSZEhK5Gv3EVjrC16i5v92ok9WS"

    val transferTxBytes: Array[Byte] =
      Base58.decode(transferTxBytesBase58)
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

    private val rdb: RDB = {
      val dir = new File(dccSettings.dbSettings.directory)
      if (!dir.isDirectory) throw new IllegalArgumentException(s"Can't find directory at '${dccSettings.dbSettings.directory}'")
      RDB.open(dccSettings.dbSettings)
    }

    val state                        = RocksDBWriter(rdb, dccSettings.blockchainSettings, dccSettings.dbSettings, dccSettings.enableLightMode)
    val environment: Environment[Id] = {
      DCCEnvironment(
        AddressScheme.current.chainId,
        Coeval.raiseError(new NotImplementedError("`tx` is not implemented")),
        Coeval(state.height),
        state,
        null,
        DirectiveSet.contractDirectiveSet,
        ByteStr.empty
      )
    }

    @TearDown
    def close(): Unit = {
      state.close()
      rdb.close()
    }

    protected def load[T](absolutePath: String)(f: String => T): Vector[T] = {
      Using.resource(scala.io.Source.fromFile(absolutePath)(using Codec.UTF8))(_.getLines().map(f).toVector)
    }
  }

  implicit class VectorOps[T](self: Vector[T]) {
    def random: T = self(ThreadLocalRandom.current().nextInt(self.size))
  }

}
