package com.decentralchain.state.diffs.ci

import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures.{LightNode, RideV6}
import com.decentralchain.lang.directives.values.V5
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.test.DomainPresets.{RideV5, DCCSettingsOps}
import com.decentralchain.test.{PropSpec, produce}
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.TxHelpers.*

class InvokeTransferBalanceErrorTest extends PropSpec with WithDomain {
  private val assetFailScript = TestCompiler(V5).compileExpression(
    s"""
       | strict c = ${(1 to 5).map(_ => "sigVerify(base58'', base58'', base58'')").mkString(" || ")}
       | if (true) then throw() else true
     """.stripMargin
  )

  property("invoke is always rejected with a lack of funds without execution of ScriptTransfer script after RideV6, corrected after LightNode") {
    val issueTx = issue(signer(10), script = Some(assetFailScript))
    val asset   = IssuedAsset(issueTx.id())
    val dApp    = TestCompiler(V5).compileContract(
      s"""
         | @Callable(i)
         | func default() = {
         |   [ScriptTransfer(i.caller, 1, base58'$asset')]
         | }
         | 
         | @Callable(i)
         | func complex() = {
         |   strict c = ${(1 to 6).map(_ => "sigVerify(base58'', base58'', base58'')").mkString(" || ")}
         |   [ScriptTransfer(i.caller, 1, base58'$asset')]
         | }
       """.stripMargin
    )
    withDomain(
      RideV5.setFeaturesHeight(RideV6 -> 6, LightNode -> 7),
      AddrWithBalance.enoughBalances(secondSigner, signer(10))
    ) { d =>
      d.appendBlock(issueTx)
      d.appendBlock(setScript(secondSigner, dApp))

      // RideV5 — always failed
      d.appendAndAssertFailed(invoke(), "Transaction is not allowed by script of the asset")
      d.appendAndAssertFailed(invoke(func = Some("complex")), "Transaction is not allowed by script of the asset")

      // RideV6 — always rejected
      d.appendBlockE(invoke()) should produce("negative asset balance")
      d.appendBlockE(invoke(func = Some("complex"))) should produce("negative asset balance")

      // LightNode — rejected or failed
      d.appendBlock()
      d.appendBlockE(invoke()) should produce("negative asset balance")
      d.appendAndAssertFailed(invoke(func = Some("complex")), "negative asset balance")
    }
  }
}
