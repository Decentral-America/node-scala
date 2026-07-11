package com.decentralchain.state.diffs.ci.sync

import com.decentralchain.TestValues
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.lang.directives.values.*
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.settings.DCCSettings
import com.decentralchain.test.{PropSpec, produce}
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.Transaction
import com.decentralchain.transaction.TxHelpers.*
import com.decentralchain.transaction.smart.InvokeScriptTransaction.Payment
import com.decentralchain.transaction.smart.SetScriptTransaction

class SyncInvokeTotalPaymentsTest extends PropSpec with WithDomain {
  import DomainPresets.*

  property("total payments limit") {
    def setDAppsCallingEachOther(syncCalls: Int, syncPayments: Int): Seq[SetScriptTransaction] = {
      val totalCalls = syncCalls + 1
      val payments   = Seq.fill(syncPayments)(s"AttachedPayment(unit, 1)").mkString(",")
      (1 to totalCalls).map { i =>
        val dApp =
          if (i < totalCalls)
            s"""
               | @Callable(i)
               | func default() = {
               |   strict r = Address(base58'${signer(i + 1).toAddress}').invoke("default", [], [$payments])
               |   []
               | }
             """.stripMargin
          else
            s"""
               | @Callable(i)
               | func default() = []
             """.stripMargin
        setScript(signer(i), TestCompiler(V5).compileContract(dApp))
      }
    }

    def setDAppsCallingFromParent(syncCalls: Int, syncPayments: Int): Seq[SetScriptTransaction] = {
      val payments         = Seq.fill(syncPayments)(s"AttachedPayment(unit, 1)").mkString(",")
      def nextCall(i: Int) = s"""strict r$i = Address(base58'${signer(i + 1).toAddress}').invoke("default", [], [$payments])"""
      val parentDApp       =
        s"""
           | @Callable(i)
           | func default() = {
           |   ${(1 to syncCalls).map(nextCall).mkString("\n")}
           |   []
           | }
         """.stripMargin
      val dApp =
        s"""
           | @Callable(i)
           | func default() = []
         """.stripMargin
      val syncDApps = (1 to syncCalls).map(i => setScript(signer(i + 1), TestCompiler(V5).compileContract(dApp)))
      syncDApps :+ setScript(signer(1), TestCompiler(V5).compileContract(parentDApp), TestValues.fee * 3)
    }

    def assert(
        settings: DCCSettings,
        setScripts: (Int, Int) => Seq[Transaction],
        syncCalls: Int,
        syncPayments: Int,
        txPayments: Int,
        error: Boolean,
        fail: Boolean = false
    ): Unit =
      withDomain(settings, AddrWithBalance.enoughBalances((1 to 101).map(signer)*)) { d =>
        d.appendBlock(setScripts(syncCalls, syncPayments)*)
        val payments = Seq.fill(txPayments)(Payment(1, Dcc))
        val tx       = invoke(signer(1).toAddress, payments = payments)
        if (error)
          if (fail) {
            d.appendAndAssertFailed(tx)
            d.liquidSnapshot.errorMessage(tx.id()).get.text should include("Invoke payments limit = 100 is exceeded")
          } else
            d.appendBlockE(tx) should produce("Invoke payments limit = 100 is exceeded")
        else
          d.appendAndAssertSucceed(tx)
      }

    assert(RideV5, setDAppsCallingEachOther, syncCalls = 100, syncPayments = 10, txPayments = 10, error = false)
    assert(RideV5, setDAppsCallingFromParent, syncCalls = 45, syncPayments = 10, txPayments = 10, error = false)
    // reduced syncCalls to avoid exceeding size error ^^^

    Seq(setDAppsCallingFromParent, setDAppsCallingEachOther)
      .foreach { setDApps =>
        assert(RideV6, setDApps, syncCalls = 9, syncPayments = 10, txPayments = 10, error = false)
        assert(RideV6, setDApps, syncCalls = 10, syncPayments = 10, txPayments = 0, error = false)
        assert(RideV6, setDApps, syncCalls = 10, syncPayments = 10, txPayments = 1, error = true)
        assert(RideV6, setDApps, syncCalls = 100, syncPayments = 1, txPayments = 1, error = true, fail = true)
      }
  }
}
