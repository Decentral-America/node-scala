package com.decentralchain.lang.v1

import com.decentralchain.account.{Address, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto.Curve25519
import com.decentralchain.lang.v1.AddressToStringBenchmark.AddressToString
import com.decentralchain.lang.v1.FunctionHeader.Native
import com.decentralchain.lang.v1.compiler.Terms.{CONST_BYTESTR, CaseObj, FUNCTION_CALL}
import com.decentralchain.lang.v1.evaluator.FunctionIds
import com.decentralchain.lang.v1.evaluator.ctx.impl.dcc.Types
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadLocalRandom

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
class AddressToStringBenchmark {
  @Benchmark
  def addressToString(bh: Blackhole, st: AddressToString): Unit =
    bh.consume(eval(st.expr))
}

object AddressToStringBenchmark {
  @State(Scope.Benchmark)
  class AddressToString {
    val publicKey = new Array[Byte](Curve25519.KeyLength)
    ThreadLocalRandom.current().nextBytes(publicKey)

    val address = Address.fromPublicKey(PublicKey(publicKey)).bytes

    val expr =
      FUNCTION_CALL(
        Native(FunctionIds.ADDRESSTOSTRING),
        List(CaseObj(Types.addressType, Map("bytes" -> CONST_BYTESTR(ByteStr(address)).explicitGet())))
      )
  }
}
