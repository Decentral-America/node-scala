package com.decentralchain.common
import java.util.concurrent.TimeUnit

import com.decentralchain.state.diffs.FeeValidation
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@Threads(4)
@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
class SponsorshipMathBenchmark {
  @Benchmark
  def bigDecimal_test(bh: Blackhole): Unit = {
    def toDcc(assetFee: Long, sponsorship: Long): Long = {
      val dcc = (BigDecimal(assetFee) * BigDecimal(FeeValidation.FeeUnit)) / BigDecimal(sponsorship)
      if (dcc > Long.MaxValue) {
        throw new java.lang.ArithmeticException("Overflow")
      }
      dcc.toLong
    }

    bh.consume(toDcc(100000, 100000000))
  }

  @Benchmark
  def bigInt_test(bh: Blackhole): Unit = {
    def toDcc(assetFee: Long, sponsorship: Long): Long = {
      val dcc = BigInt(assetFee) * FeeValidation.FeeUnit / sponsorship
      dcc.bigInteger.longValueExact()
    }

    bh.consume(toDcc(100000, 100000000))
  }
}
