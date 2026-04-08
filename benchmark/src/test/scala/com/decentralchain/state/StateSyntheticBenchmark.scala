package com.decentralchain.state

import java.util.concurrent.TimeUnit

import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.lang.directives.values.*
import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.lang.utils.*
import com.decentralchain.lang.v1.compiler.ExpressionCompiler
import com.decentralchain.lang.v1.parser.Parser
import com.decentralchain.settings.FunctionalitySettings
import com.decentralchain.state.StateSyntheticBenchmark.*
import com.decentralchain.transaction.Asset.Waves
import com.decentralchain.transaction.{Proofs, Transaction}
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.transfer.*
import org.openjdk.jmh.annotations.*
import org.scalacheck.Gen

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
class StateSyntheticBenchmark {

  @Benchmark
  def appendBlock_test(db: St): Unit = db.genAndApplyNextBlock()

  @Benchmark
  def appendBlock_smart_test(db: SmartSt): Unit = db.genAndApplyNextBlock()

}

object StateSyntheticBenchmark {

  @State(Scope.Benchmark)
  class St extends BaseState {
    protected override def txGenP(sender: KeyPair, ts: Long): Gen[Transaction] =
      for {
        amount    <- Gen.choose(1L, dcc(1))
        recipient <- accountGen
      } yield TransferTransaction.create(1.toByte, sender.publicKey, recipient.toAddress, Waves, amount, Waves, 100000, ByteStr.empty, ts, Proofs.empty).map(_.signWith(sender.privateKey)).explicitGet()
  }

  @State(Scope.Benchmark)
  class SmartSt extends BaseState {

    override protected def updateFunctionalitySettings(base: FunctionalitySettings): FunctionalitySettings = {
      base.copy(preActivatedFeatures = Map(4.toShort -> 0))
    }

    protected override def txGenP(sender: KeyPair, ts: Long): Gen[Transaction] =
      for {
        recipient: KeyPair <- accountGen
        amount             <- Gen.choose(1L, dcc(1))
      } yield TransferTransaction
        .create(2.toByte, sender.publicKey, recipient.toAddress, Waves, amount, Waves, 1000000, ByteStr.empty, ts, Proofs.empty)
        .map(_.signWith(sender.privateKey))
        .explicitGet()

    @Setup
    override def init(): Unit = {
      super.init()

      val textScript    = "sigVerify(tx.bodyBytes,tx.proofs[0],tx.senderPublicKey)"
      val untypedScript = Parser.parseExpr(textScript).get.value
      val typedScript   = ExpressionCompiler(compilerContext(V1, Expression, isAssetScript = false), V1, untypedScript).explicitGet()._1

      val setScriptBlock = nextBlock(
        Seq(
          SetScriptTransaction
            .create(1.toByte, richAccount.publicKey, Some(ExprScript(typedScript).explicitGet()), 1000000, System.currentTimeMillis(), Proofs.empty)
            .map(_.signWith(richAccount.privateKey))
            .explicitGet()
        )
      )

      applyBlock(setScriptBlock)
    }
  }

}
