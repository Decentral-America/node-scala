package com.decentralchain.state.diffs.ci.sync

import com.decentralchain.TransactionGenBase
import com.decentralchain.account.Address
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithDomain
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.lang.directives.values.V5
import com.decentralchain.lang.script.Script
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.state.diffs.ENOUGH_AMT
import com.decentralchain.state.diffs.ci.ciFee
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.transaction.{GenesisTransaction, TxHelpers, TxVersion}

class SyncDAppBalanceCheckTest extends PropSpec with WithDomain with TransactionGenBase {

  private val time = new TestTime
  private def ts   = time.getTimestamp()

  private def dApp1Script(dApp2: Address): Script =
    TestCompiler(V5).compileContract(
      s"""
         | @Callable(i)
         | func default() = {
         |    strict r = Address(base58'$dApp2').invoke("default", [], [AttachedPayment(unit, 100)])
         |    []
         | }
       """.stripMargin
    )

  private val dApp2Script: Script =
    TestCompiler(V5).compileContract(
      s"""
         | @Callable(i)
         | func default() =
         |   [
         |     ScriptTransfer(i.caller, 100, unit)
         |   ]
       """.stripMargin
    )

  private val scenario =
    for {
      invoker <- accountGen
      dApp1   <- accountGen
      dApp2   <- accountGen
      fee     <- ciFee()
      gTx1     = GenesisTransaction.create(invoker.toAddress, ENOUGH_AMT, ts).explicitGet()
      gTx2     = GenesisTransaction.create(dApp1.toAddress, 0.01.dcc, ts).explicitGet()
      gTx3     = GenesisTransaction.create(dApp2.toAddress, ENOUGH_AMT, ts).explicitGet()
      ssTx1    = TxHelpers.setScript(dApp1, dApp1Script(dApp2.toAddress), 0.01.waves, 1.toByte)
      ssTx2    = TxHelpers.setScript(dApp2, dApp2Script, 0.01.waves, 1.toByte)
      invokeTx = () => TxHelpers.invoke(dApp1.toAddress, invoker = invoker, fee = fee, version = TxVersion.V3, timestamp = ts)
    } yield (Seq(gTx1, gTx2, gTx3, ssTx1, ssTx2), invokeTx)

  property("temporary negative balance of sync call produces error") {
    val (preparingTxs, invoke) = scenario.sample.get
    val settings =
      DomainPresets.RideV5
        .configure(_.copy(enforceTransferValidationAfter = 2))
        .setFeaturesHeight(BlockchainFeatures.RideV6 -> 4)

    withDomain(settings) { d =>
      d.appendBlock(preparingTxs*)

      val invoke1 = invoke()
      d.appendAndCatchError(invoke1).toString should include("Negative dcc balance")
    }
  }
}
