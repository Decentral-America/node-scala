package com.decentralchain.state

import java.util.concurrent.TimeUnit
import com.decentralchain.account.{Address, Alias}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.database.{DBExt, KeyTag, Keys}
import com.decentralchain.lang.v1.traits.DataType
import com.decentralchain.lang.v1.traits.DataType.{Boolean, ByteArray, Long}
import com.decentralchain.lang.v1.traits.domain.Recipient
import com.decentralchain.state.TxMeta.Status
import com.decentralchain.transaction.DataTransaction
import com.decentralchain.transaction.transfer.TransferTransaction
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.ThreadLocalRandom

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 100)
@Measurement(iterations = 100)
class DCCEnvironmentRebenchmark {
  import DCCEnvironmentRebenchmark.*

  @Benchmark
  def resolveAlias(bh: Blackhole, st: St): Unit = {
    val useUnexisting = ThreadLocalRandom.current().nextBoolean()
    if (useUnexisting) {
      bh.consume(st.environment.resolveAlias("unexisting_alias_zzzzz"))
    } else {
      val aliasNr = ThreadLocalRandom.current().nextInt(st.allAliases.size)
      bh.consume(st.environment.resolveAlias(st.allAliases(aliasNr).name))
    }
  }

  @Benchmark
  def dccBalanceOf(bh: Blackhole, st: St): Unit = {
    val useUnexisting = ThreadLocalRandom.current().nextBoolean()
    if (useUnexisting) {
      bh.consume(st.environment.accountBalanceOf(Recipient.Address(ByteStr.fromBytes(1, 2, 3)), None))
    } else {
      val addressNr = ThreadLocalRandom.current().nextInt(st.allAddresses.size)
      bh.consume(st.environment.accountBalanceOf(st.allAddresses(addressNr), None))
    }
  }

  @Benchmark
  def assetBalanceOf(bh: Blackhole, st: St): Unit = {
    val useUnexisting = ThreadLocalRandom.current().nextBoolean()
    val addressNr     = ThreadLocalRandom.current().nextInt(st.allAddresses.size)
    if (useUnexisting) {
      bh.consume(st.environment.accountBalanceOf(st.allAddresses(addressNr), Some(Array[Byte](1, 2, 3))))
    } else {
      val aliasNr = ThreadLocalRandom.current().nextInt(st.allAssets.size)
      bh.consume(st.environment.accountBalanceOf(st.allAddresses(addressNr), Some(st.allAssets(aliasNr))))
    }
  }

  @Benchmark
  def assetInfo(bh: Blackhole, st: St): Unit = {
    val useUnexisting = ThreadLocalRandom.current().nextBoolean()
    if (useUnexisting) {
      bh.consume(st.environment.assetInfoById(Array[Byte](1, 2, 3)))
    } else {
      val aliasNr = ThreadLocalRandom.current().nextInt(st.allAssets.size)
      bh.consume(st.environment.assetInfoById(st.allAssets(aliasNr)))
    }
  }

  @Benchmark
  def transferTransactionById(bh: Blackhole, st: St): Unit = {
    val useUnexisting = ThreadLocalRandom.current().nextBoolean()
    if (useUnexisting) {
      val transactionNr = ThreadLocalRandom.current().nextInt(st.allTransactions.size)
      bh.consume(st.environment.transferTransactionById(st.allTransactions(transactionNr)))
    } else {
      val transactionNr = ThreadLocalRandom.current().nextInt(st.transferTransactions.size)
      bh.consume(st.environment.transferTransactionById(st.transferTransactions(transactionNr).arr))
    }
  }

  @Benchmark
  def transactionHeightById(bh: Blackhole, st: St): Unit = {
    val useUnexisting = ThreadLocalRandom.current().nextBoolean()
    if (useUnexisting) {
      bh.consume(st.environment.transactionHeightById(Array[Byte](1, 2, 3)))
    } else {
      val transactionNr = ThreadLocalRandom.current().nextInt(st.allTransactions.size)
      bh.consume(st.environment.transactionHeightById(st.allTransactions(transactionNr)))
    }
  }

  @Benchmark
  def blockInfoByHeight(bh: Blackhole, st: St): Unit = {
    val indexNr = ThreadLocalRandom.current().nextInt(st.heights.size)
    bh.consume(st.environment.blockInfoByHeight(st.heights(indexNr)))
  }

  @Benchmark
  def dataEntries(bh: Blackhole, st: St): Unit = {
    val useUnexisting = ThreadLocalRandom.current().nextBoolean()
    if (useUnexisting) {
      val addressNr = ThreadLocalRandom.current().nextInt(st.allAddresses.size)
      bh.consume(st.environment.data(st.allAddresses(addressNr), "unexisting_key", Long))
    } else {
      val transactionNr = ThreadLocalRandom.current().nextInt(st.dataEntries.size)
      val dataEntry     = st.dataEntries(transactionNr)._1
      val address       = st.dataEntries(transactionNr)._2
      val t             =
        dataEntry.`type` match {
          case "string"  => DataType.String
          case "integer" => Long
          case "boolean" => Boolean
          case "binary"  => ByteArray
        }
      bh.consume(st.environment.data(address, dataEntry.key, t))
    }
  }

  @Benchmark
  def biggestDataEntries(bh: Blackhole, st: St): Unit = {
    val address = Recipient.Address(
      ByteStr(Address.fromString("3PFfUN4dRAyMN4nxYayES1CRZHJjS8JVCHf", None).explicitGet().bytes)
    )
    val checkBinaryOrString = ThreadLocalRandom.current().nextBoolean()
    if (checkBinaryOrString) {
      bh.consume(st.environment.data(address, "bigBinary", ByteArray))
    } else {
      bh.consume(st.environment.data(address, "bigString", DataType.String))
    }
  }
}

object DCCEnvironmentRebenchmark {
  class St extends DBState {
    lazy val allAliases: Vector[Alias] = {
      val builder = Vector.newBuilder[Alias]
      rdb.db.iterateOver(KeyTag.AddressIdOfAlias) { e =>
        builder += Alias.fromBytes(e.getKey.drop(2), None).explicitGet()
      }
      builder.result()
    }

    lazy val allAssets: Vector[Array[Byte]] = {
      val builder = Vector.newBuilder[Array[Byte]]
      rdb.db.iterateOver(KeyTag.AssetDetailsHistory) { e =>
        builder += e.getKey.drop(2)
      }
      builder.result()
    }

    lazy val allAddresses: IndexedSeq[Recipient.Address] = {
      val builder = Vector.newBuilder[Recipient.Address]
      rdb.db.iterateOver(KeyTag.AddressId) { entry =>
        builder += Recipient.Address(ByteStr(entry.getKey.drop(2)))
      }
      builder.result()
    }

    lazy val allTransactions: IndexedSeq[Array[Byte]] = {
      val txCountAtHeight =
        Map.empty[Int, Int].withDefault(h => rdb.db.get(Keys.blockMetaAt(Height(h))).fold(0)(_.transactionCount))

      1.to(environment.height.toInt, 100)
        .flatMap { h =>
          val txCount = txCountAtHeight(h)
          if (txCount == 0)
            None
          else
            rdb.db.get(Keys.transactionAt(Height(h), TxNum(ThreadLocalRandom.current().nextInt(txCount).toShort), rdb.txHandle)).map(_._2.id().arr)
        }
    }

    lazy val dataEntries: IndexedSeq[(DataEntry[?], Recipient.Address)] = {
      val txCountAtHeight =
        Map.empty[Int, Int].withDefault(h => rdb.db.get(Keys.blockMetaAt(Height(h))).fold(0)(_.transactionCount))

      1.to(environment.height.toInt, 10)
        .flatMap { h =>
          val txCount = txCountAtHeight(h)
          if (txCount == 0)
            None
          else
            rdb.db
              .get(Keys.transactionAt(Height(h), TxNum(ThreadLocalRandom.current().nextInt(txCount).toShort), rdb.txHandle))
              .collect {
                case (meta, dataTx: DataTransaction) if meta.status == Status.Succeeded && dataTx.data.nonEmpty =>
                  (
                    dataTx.data(ThreadLocalRandom.current().nextInt(dataTx.data.length)),
                    Recipient.Address(ByteStr(dataTx.sender.toAddress.bytes))
                  )
              }
        }
    }

    lazy val transferTransactions: IndexedSeq[ByteStr] = {
      val txCountAtHeight =
        Map.empty[Int, Int].withDefault(h => rdb.db.get(Keys.blockMetaAt(Height(h))).fold(0)(_.transactionCount))

      1.to(environment.height.toInt, 100)
        .flatMap { h =>
          val txCount = txCountAtHeight(h)
          if (txCount == 0)
            None
          else
            rdb.db
              .get(Keys.transactionAt(Height(h), TxNum(ThreadLocalRandom.current().nextInt(txCount).toShort), rdb.txHandle))
              .collect { case (meta, transferTx: TransferTransaction) if meta.status == Status.Succeeded => transferTx.id() }
        }
    }

    lazy val heights: IndexedSeq[Int] =
      1.to(environment.height.toInt, 1000).toVector
  }
}
