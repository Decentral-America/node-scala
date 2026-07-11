package com.decentralchain.state.diffs.ci.sync

import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures.LightNode
import com.decentralchain.lang.directives.values.V7
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.TxHelpers.*

class SyncDAppGeneratingBalanceTest extends PropSpec with WithDomain {
  property("sync balance changes should be taken into account for the generatingBalance field") {
    val amount = 777
    val dApp   = TestCompiler(V7).compileContract(
      s"""
         | @Callable(i)
         | func default() = {
         |   strict generatingBefore = i.caller.dccBalance().generating
         |   strict result = Address(base58'$defaultAddress').invoke("call", [], [AttachedPayment(unit, $amount)])
         |   strict generatingAfter = i.caller.dccBalance().generating
         |   [
         |     IntegerEntry("generatingDiff", generatingBefore - generatingAfter)
         |   ]
         | }
         |
         | @Callable(i)
         | func call() = []
       """.stripMargin
    )
    withDomain(
      BlockRewardDistribution.setFeaturesHeight(LightNode -> 4),
      AddrWithBalance.enoughBalances(defaultSigner, secondSigner)
    ) { d =>
      d.appendBlock(setScript(defaultSigner, dApp), setScript(secondSigner, dApp))

      d.appendAndAssertSucceed(invoke(secondAddress, invoker = secondSigner))
      d.liquidSnapshot.accountData.head._2.head._2.value shouldBe 0

      d.appendAndAssertSucceed(invoke(secondAddress, invoker = secondSigner))
      d.liquidSnapshot.accountData.head._2.head._2.value shouldBe amount
    }
  }
}
