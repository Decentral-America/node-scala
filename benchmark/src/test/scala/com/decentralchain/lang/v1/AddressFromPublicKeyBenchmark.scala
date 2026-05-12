package com.decentralchain.lang.v1
import com.decentralchain.account.PublicKey
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.directives.DirectiveSet
import com.decentralchain.lang.directives.values.{Account, Expression, V6}
import com.decentralchain.lang.utils.lazyContexts
import com.decentralchain.lang.v1.EnvironmentFunctionsBenchmark.curve25519
import com.decentralchain.lang.v1.compiler.TestCompiler
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
class AddressFromPublicKeyBenchmark {
  @Benchmark
  def addressFromPublicKeyWaves(s: PkSt, bh: Blackhole): Unit = bh.consume(eval(s.ctx, s.exprWaves, V6))

  @Benchmark
  def addressFromPublicKeyEth(s: PkSt, bh: Blackhole): Unit = bh.consume(eval(s.ctx, s.exprEth, V6))
}

@State(Scope.Benchmark)
class PkSt {
  val ds  = DirectiveSet(V6, Account, Expression).fold(null, identity)
  val ctx = lazyContexts((ds, true, true, true)).value().evaluationContext(EnvironmentFunctionsBenchmark.environment)

  val wavesPk   = ByteStr(curve25519.generateKeypair._2)
  val exprWaves = TestCompiler(V6).compileExpression(s"addressFromPublicKey(base58'$wavesPk')").expr
  val exprEth   = TestCompiler(V6).compileExpression(s"addressFromPublicKey(base58'${PublicKey(wavesPk.arr ++ wavesPk.arr)}')").expr
}
