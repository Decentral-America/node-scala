package com.decentralchain.state.diffs.smart

import com.decentralchain.TransactionGenBase
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithDomain
import com.decentralchain.features.BlockchainFeatures.*
import com.decentralchain.lang.directives.values.{V5, V6}
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.settings.TestFunctionalitySettings
import com.decentralchain.state.diffs.ENOUGH_AMT
import com.decentralchain.state.diffs.ci.ciFee
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.transaction.TxHelpers.{defaultAddress, defaultSigner}
import com.decentralchain.transaction.{GenesisTransaction, TxHelpers, TxVersion}

class EstimationSwitchTest extends PropSpec with WithDomain with TransactionGenBase {
  private val time = new TestTime
  private def ts   = time.getTimestamp()

  private val dAppScript =
    TestCompiler(V5).compileContract(
      s"""
         | @Callable(i)
         | func default() = {
         |   if (1 != 1) then [] else []
         | }
       """.stripMargin
    )

  private val settings =
    TestFunctionalitySettings.withFeaturesByHeight(BlockV5 -> 0, SynchronousCalls -> 0, RideV6 -> 3)

  property("both evaluator and estimator complexities should be decreased after RideV6 activation") {
    val invoker   = accountGen.sample.get
    val dApp      = accountGen.sample.get
    val fee       = ciFee().sample.get
    val genesis1  = GenesisTransaction.create(invoker.toAddress, ENOUGH_AMT, ts).explicitGet()
    val genesis2  = GenesisTransaction.create(dApp.toAddress, ENOUGH_AMT, ts).explicitGet()
    val setScript = () => TxHelpers.setScript(dApp, dAppScript, fee, 1.toByte)
    val invoke    = () => TxHelpers.invoke(dApp.toAddress, invoker = invoker, fee = fee, version = TxVersion.V3, timestamp = ts)

    withDomain(domainSettingsWithFS(settings)) { d =>
      d.appendBlock(genesis1, genesis2)

      d.appendBlock(setScript(), invoke())
      d.liquidSnapshot.accountScripts.head._2.get.complexitiesByEstimator(3)("default") shouldBe 5
      d.liquidSnapshot.scriptsComplexity shouldBe 7
      // bigger than estimator because of ignoring predefined user function complexities

      d.appendBlock(setScript(), invoke())
      d.liquidSnapshot.accountScripts.head._2.get.complexitiesByEstimator(3)("default") shouldBe 1
      d.liquidSnapshot.scriptsComplexity shouldBe 1
    // condition decreased by 1,
    // accessing to ref ([] = nil) decreased by 1,
    // != decreased by 4 (because of using predefined user function complexities)
    }
  }

  property("estimator global vars fixes activation") {
    withDomain(DomainPresets.ContinuationTransaction.setFeaturesHeight(LightNode -> 2)) { d =>
      val dApp = TestCompiler(V6).compileContract(
        """
          | @Callable(i)
          | func overlapCase() = {
          |   func f(a: Boolean) = a
          |   let a = groth16Verify(base58'', base58'', base58'')
          |   if (f(true)) then [] else []
          | }
          |
          | @Callable(i)
          | func redundantOverheadCase() = {
          |   let a = sigVerify(base58'', base58'', base58'')
          |   func f() = a
          |   if (f()) then [] else []
          | }
        """.stripMargin
      )

      d.appendBlock(TxHelpers.setScript(defaultSigner, dApp))
      d.blockchain.accountScript(defaultAddress).get.complexitiesByEstimator(3) shouldBe
        Map("overlapCase" -> 2701, "redundantOverheadCase" -> 181)

      d.appendBlock(TxHelpers.setScript(defaultSigner, dApp))
      d.blockchain.accountScript(defaultAddress).get.complexitiesByEstimator(3) shouldBe
        Map("overlapCase" -> 1, "redundantOverheadCase" -> 180)
    }
  }
}
