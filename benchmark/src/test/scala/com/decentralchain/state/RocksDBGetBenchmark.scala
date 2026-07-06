package com.decentralchain.state

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory
import com.decentralchain.database.{DirectBufferPool, RDB}
import com.decentralchain.settings.{DCCSettings, loadConfig}
import com.decentralchain.state.RocksDBGetBenchmark.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.rocksdb.{ReadOptions, WriteBatch, WriteOptions}

@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 100)
class RocksDBGetBenchmark {
  @Benchmark
  def simpleGet(st: BaseSt, bh: Blackhole): Unit = {
    bh.consume(st.kvs.foreach { case (key, _) =>
      st.rdb.db.get(st.readOptions, key)
    })
  }

  @Benchmark
  def byteBufferGet(st: BaseSt, bh: Blackhole): Unit = {
    bh.consume {
      st.kvs.foreach { case (key, value) =>
        val keyBuffer = DirectBufferPool.get(key.length)
        keyBuffer.put(key).flip()
        val valBuffer = DirectBufferPool.get(value.length)

        st.rdb.db.get(st.readOptions, keyBuffer, valBuffer)

        DirectBufferPool.release(keyBuffer)
        DirectBufferPool.release(valBuffer)
      }
    }
  }
}

object RocksDBGetBenchmark {

  @State(Scope.Benchmark)
  class BaseSt {
    private val dccSettings: DCCSettings =
      DCCSettings.fromRootConfig(loadConfig(ConfigFactory.load()))

    val rdb: RDB = {
      val dir = Files.createTempDirectory("state-synthetic").toAbsolutePath.toString
      RDB.open(dccSettings.dbSettings.copy(directory = dir))
    }

    val kvs: Map[Array[Byte], Array[Byte]] = (1 to 10000).map { idx =>
      s"key$idx".getBytes -> s"value$idx".getBytes
    }.toMap

    val readOptions: ReadOptions = new ReadOptions()

    private val wb: WriteBatch = new WriteBatch()
    kvs.foreach { case (key, value) =>
      wb.put(key, value)
    }
    rdb.db.write(new WriteOptions(), wb)

    @TearDown
    def close(): Unit = {
      rdb.close()
    }
  }
}
