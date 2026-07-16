package com.decentralchain.state.diffs.smart.scenarios

import cats.syntax.semigroup.*
import com.decentralchain.api.common.CommonAccountsApi
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithState
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.lang.Global
import com.decentralchain.lang.directives.DirectiveSet
import com.decentralchain.lang.directives.values.{Asset as AssetType, *}
import com.decentralchain.lang.script.Script
import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.lang.v1.compiler.{ExpressionCompiler, TestCompiler}
import com.decentralchain.lang.v1.evaluator.ctx.impl.dcc.DccContext
import com.decentralchain.lang.v1.evaluator.ctx.impl.{CryptoContext, PureContext}
import com.decentralchain.lang.v1.parser.*
import com.decentralchain.lang.v1.traits.Environment
import com.decentralchain.settings.{Constants, FunctionalitySettings, TestFunctionalitySettings}
import com.decentralchain.state.*
import com.decentralchain.state.diffs.*
import com.decentralchain.test.*
import com.decentralchain.transaction.*
import com.decentralchain.transaction.Asset.*

class BalancesV4Test extends PropSpec with WithState {

  val MinFee: Long            = Constants.UnitsInDcc / 1000L
  val DataTxFee: Long         = 15000000L
  val InvokeScriptTxFee: Long = 15000000L
  val MassTransferTxFee: Long = 15000000L
  val SetScriptFee: Long      = Constants.UnitsInDcc / 1000L
  val SetAssetScriptFee: Long = Constants.UnitsInDcc

  val rideV4Activated: FunctionalitySettings = TestFunctionalitySettings.Enabled.copy(preActivatedFeatures =
    Map(
      BlockchainFeatures.Ride4DApps.id    -> 0,
      BlockchainFeatures.SmartAccounts.id -> 0,
      BlockchainFeatures.BlockV5.id       -> 0
    )
  )

  private val preconditionsAndTransfer = {
    val master = TxHelpers.signer(0)
    val acc1   = TxHelpers.signer(1)
    val dapp   = TxHelpers.signer(2)

    val genesis = Seq(
      TxHelpers.genesis(master.toAddress),
      TxHelpers.genesis(acc1.toAddress, 25 * Constants.UnitsInDcc + 3 * MinFee),
      TxHelpers.genesis(dapp.toAddress, 10 * Constants.UnitsInDcc + SetScriptFee + 2 * InvokeScriptTxFee + 1 * Constants.UnitsInDcc)
    )
    val alias       = "alias"
    val createAlias = TxHelpers.createAlias(alias, acc1, MinFee)
    val setScript   = TxHelpers.setScript(dapp, script(alias), SetScriptFee)
    val invoke      = TxHelpers.invoke(dapp.toAddress, func = Some("bar"), invoker = master, fee = InvokeScriptTxFee)
    val lease1      = TxHelpers.lease(acc1, dapp.toAddress, 10 * Constants.UnitsInDcc, MinFee)
    val lease2      = TxHelpers.lease(acc1, dapp.toAddress, 10 * Constants.UnitsInDcc, MinFee)
    val leaseD      = TxHelpers.lease(dapp, acc1.toAddress, 1 * Constants.UnitsInDcc, MinFee)
    val cancel1     = TxHelpers.leaseCancel(lease1.id(), acc1, MinFee)
    val transfer    = TxHelpers.transfer(dapp, acc1.toAddress, 1 * Constants.UnitsInDcc + MinFee, fee = InvokeScriptTxFee)

    ((genesis :+ createAlias) ++ Seq(setScript, lease1, lease2), Seq(cancel1, leaseD, transfer), acc1, dapp, invoke)
  }

  def script(a: String): Script = {
    val script =
      s"""
         | {-#STDLIB_VERSION 4 #-}
         | {-#SCRIPT_TYPE ACCOUNT #-}
         | {-#CONTENT_TYPE DAPP #-}
         |
         | @Callable(i)
         | func bar() = {
         |  let balance = dccBalance(Alias("$a"))
         |   [
         |     IntegerEntry("available", balance.available),
         |     IntegerEntry("regular", balance.regular),
         |     IntegerEntry("generating", balance.generating),
         |     IntegerEntry("effective", balance.effective)
         |   ]
         | }
      """.stripMargin
    TestCompiler(V4).compileContract(script)
  }

  property("Dcc balance details") {
    val (genesis, b, acc1, dapp, ci) = preconditionsAndTransfer
    assertDiffAndState(
      Seq(TestBlock.create(genesis)) ++
        (0 to 1000).map(_ => TestBlock.create(Seq())) ++
        Seq(TestBlock.create(b)),
      TestBlock.create(Seq(ci)),
      rideV4Activated
    ) { case (snapshot, blockchain) =>
      val apiBalance =
        CommonAccountsApi(() => SnapshotBlockchain(blockchain, snapshot), rdb, blockchain)
          .balanceDetails(acc1.toAddress)
          .explicitGet()
      val data = snapshot.accountData(dapp.toAddress)
      data("available") shouldBe IntegerDataEntry("available", apiBalance.available)
      apiBalance.available shouldBe 16 * Constants.UnitsInDcc
      data("regular") shouldBe IntegerDataEntry("regular", apiBalance.regular)
      apiBalance.regular shouldBe 26 * Constants.UnitsInDcc
      data("generating") shouldBe IntegerDataEntry("generating", apiBalance.generating)
      apiBalance.generating shouldBe 5 * Constants.UnitsInDcc
      data("effective") shouldBe IntegerDataEntry("effective", apiBalance.effective)
      apiBalance.effective shouldBe 17 * Constants.UnitsInDcc

    }
  }

  property("Asset balance change while processing script result") {
    val a                                 = 10000000000L
    def assetScript(acc: ByteStr): Script = {
      val ctx = {
        val directives = DirectiveSet(V4, AssetType, Expression).explicitGet()
        PureContext.build(V4, useNewPowPrecision = true).withEnvironment[Environment] |+|
          CryptoContext.build(Global, V4, fixEcrecover = true).withEnvironment[Environment] |+|
          DccContext.build(Global, directives, fixBigScriptField = true)
      }

      val script =
        s"""
           | {-# STDLIB_VERSION 4 #-}
           | {-# CONTENT_TYPE EXPRESSION #-}
           | {-# SCRIPT_TYPE ASSET #-}
           |
           | assetBalance(Address(base58'$acc'), this.id) == $a && assetBalance(Alias("alias"), this.id) == $a
        """.stripMargin
      val parsedScript = Parser.parseExpr(script).get.value
      ExprScript(V4, ExpressionCompiler(ctx.compilerContext, V4, parsedScript).explicitGet()._1)
        .explicitGet()
    }

    def dappScript(acc: ByteStr, asset: ByteStr): Script = {
      val script =
        s"""
           | {-#STDLIB_VERSION 4 #-}
           | {-#SCRIPT_TYPE ACCOUNT #-}
           | {-#CONTENT_TYPE DAPP #-}
           |
           | @Callable(i)
           | func bar() = {
           |   [
           |    ScriptTransfer(Address(base58'$acc'), 1, base58'$asset'),
           |    Reissue(base58'$asset', 2, false)
           |   ]
           | }
        """.stripMargin
      TestCompiler(V4).compileContract(script)
    }

    val acc1 = TxHelpers.signer(0)
    val acc2 = TxHelpers.signer(1)

    val genesis = Seq(
      TxHelpers.genesis(acc1.toAddress),
      TxHelpers.genesis(acc2.toAddress)
    )
    val createAlias = TxHelpers.createAlias("alias", acc2, MinFee)
    val issue       = TxHelpers.issue(acc1, 10000000000L, script = Some(assetScript(ByteStr(acc1.toAddress.bytes))), fee = SetAssetScriptFee)
    val setScript   = TxHelpers.setScript(acc1, dappScript(ByteStr(acc2.toAddress.bytes), issue.id()), SetScriptFee)
    val invoke      = TxHelpers.invoke(acc1.toAddress, func = Some("bar"), invoker = acc1, fee = InvokeScriptTxFee)

    assertDiffAndState(Seq(TestBlock.create(genesis :+ createAlias :+ issue :+ setScript)), TestBlock.create(Seq(invoke)), rideV4Activated) {
      case (d, s) =>
        val error = d.scriptResults(invoke.id()).error
        error.get.code shouldBe 3
        error.get.text should include("Transaction is not allowed by script of the asset")
        s.balance(acc1.toAddress, IssuedAsset(issue.id())) shouldBe a
    }
  }

  property("Dcc balance change while processing script result") {
    val w                                 = ENOUGH_AMT - SetScriptFee - SetAssetScriptFee
    def assetScript(acc: ByteStr): Script = {
      val ctx = {
        val directives = DirectiveSet(V4, AssetType, Expression).explicitGet()
        PureContext.build(V4, useNewPowPrecision = true).withEnvironment[Environment] |+|
          CryptoContext.build(Global, V4, fixEcrecover = true).withEnvironment[Environment] |+|
          DccContext.build(Global, directives, fixBigScriptField = true)
      }

      val script =
        s"""
           | {-# STDLIB_VERSION 4 #-}
           | {-# CONTENT_TYPE EXPRESSION #-}
           | {-# SCRIPT_TYPE ASSET #-}
           |
           | dccBalance(Address(base58'$acc')).regular == $w
        """.stripMargin
      val parsedScript = Parser.parseExpr(script).get.value
      ExprScript(V4, ExpressionCompiler(ctx.compilerContext, V4, parsedScript).explicitGet()._1)
        .explicitGet()
    }

    def dappScript(acc: ByteStr, asset: ByteStr): Script = {
      val script =
        s"""
           | {-#STDLIB_VERSION 4 #-}
           | {-#SCRIPT_TYPE ACCOUNT #-}
           | {-#CONTENT_TYPE DAPP #-}
           |
           | @Callable(i)
           | func bar() = {
           |   [
           |    ScriptTransfer(Address(base58'$acc'), 1, unit),
           |    Reissue(base58'$asset', 1, false)
           |   ]
           | }
        """.stripMargin
      TestCompiler(V4).compileContract(script)
    }

    val acc1 = TxHelpers.signer(0)
    val acc2 = TxHelpers.signer(1)

    val genesis = Seq(
      TxHelpers.genesis(acc1.toAddress),
      TxHelpers.genesis(acc2.toAddress)
    )
    val issue     = TxHelpers.issue(acc1, 10000000000L, script = Some(assetScript(ByteStr(acc1.toAddress.bytes))), fee = SetAssetScriptFee)
    val setScript = TxHelpers.setScript(acc1, dappScript(ByteStr(acc2.toAddress.bytes), issue.id()), SetScriptFee)
    val invoke    = TxHelpers.invoke(acc1.toAddress, func = Some("bar"), invoker = acc2, fee = InvokeScriptTxFee)

    assertDiffAndState(Seq(TestBlock.create(genesis :+ issue :+ setScript)), TestBlock.create(Seq(invoke)), rideV4Activated) { case (d, s) =>
      val error = d.scriptResults(invoke.id()).error
      error.get.code shouldBe 3
      error.get.text should include("Transaction is not allowed by script of the asset")
      s.dccPortfolio(acc1.toAddress).balance shouldBe w
    }
  }

}
