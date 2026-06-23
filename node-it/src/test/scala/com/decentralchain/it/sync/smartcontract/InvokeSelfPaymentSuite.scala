package com.decentralchain.it.sync.smartcontract

import com.typesafe.config.Config
import com.decentralchain.api.http.ApiError.ScriptExecutionError
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.BaseFunSuite
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.sync.*
import com.decentralchain.lang.v1.compiler.Terms.CONST_STRING
import com.decentralchain.lang.v1.estimator.v2.ScriptEstimatorV2
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.transaction.smart.InvokeScriptTransaction
import com.decentralchain.transaction.smart.script.ScriptCompiler
import com.decentralchain.transaction.transfer.MassTransferTransaction.Transfer
import com.decentralchain.test.*
import org.scalatest.CancelAfterFailure

class InvokeSelfPaymentSuite extends BaseFunSuite with CancelAfterFailure {
  import com.decentralchain.it.NodeConfigs.*
  override protected val nodeConfigs: Seq[Config] = Seq(Miners(3).quorum(0))

  private lazy val caller = sender.keyPair
  private val dAppV4      = TxHelpers.signer(1002)
  private val dAppV3      = TxHelpers.signer(1003)

  private lazy val issueTx = TxHelpers.issue(caller)

  private lazy val asset1   = issueTx.asset
  private lazy val asset1Id = asset1.id.toString

  private lazy val dAppV3Address = dAppV3.toAddress.toString
  private lazy val dAppV4Address = dAppV4.toAddress.toString

  test("prerequisite: set contract") {
    sender.massTransfer(caller, List(
      Transfer(dAppV4.toAddress.toString, 100.waves),
      Transfer(dAppV3.toAddress.toString, 100.waves),
    ), 0.005.waves, waitForTx = true)
    sender.signedBroadcast(issueTx.json(), true)

    val sourceV4 =
      """{-# STDLIB_VERSION 4 #-}
        |{-# CONTENT_TYPE DAPP #-}
        |{-# SCRIPT_TYPE ACCOUNT #-}
        |
        |@Callable(inv)
        |func default() = nil
        |
        |@Callable(inv)
        |func paySelf(asset: String) = {
        |  let id = if asset == "DCC" then unit else fromBase58String(asset)
        |  [ ScriptTransfer(this, 1, id) ]
        |}
      """.stripMargin
    val scriptV4 = ScriptCompiler.compile(sourceV4, ScriptEstimatorV2).explicitGet()._1.bytes().base64
    sender.setScript(dAppV4, Some(scriptV4), setScriptFee)

    val sourceV3 =
      """{-# STDLIB_VERSION 3 #-}
        |{-# CONTENT_TYPE DAPP #-}
        |{-# SCRIPT_TYPE ACCOUNT #-}
        |
        |@Callable(inv)
        |func default() = TransferSet([])
        |
        |@Callable(inv)
        |func paySelf(asset: String) = {
        |  let id = if asset == "DCC" then unit else fromBase58String(asset)
        |  TransferSet([ ScriptTransfer(this, 1, id) ])
        |}
      """.stripMargin
    val scriptV3 = ScriptCompiler.compile(sourceV3, ScriptEstimatorV2).explicitGet()._1.bytes().base64
    sender.setScript(dAppV3, Some(scriptV3), setScriptFee)

    sender.massTransfer(
      caller,
      List(Transfer(dAppV4Address, 1000), Transfer(dAppV3Address, 1000)),
      smartMinFee,
      assetId = Some(asset1Id),
      waitForTx = true
    )
  }

  test("V4: can't invoke itself with payment") {
    for (
      payment <- List(
        Seq(InvokeScriptTransaction.Payment(1, Dcc)),
        Seq(InvokeScriptTransaction.Payment(1, asset1)),
        Seq(InvokeScriptTransaction.Payment(1, Dcc), InvokeScriptTransaction.Payment(1, asset1))
      )
    ) {
      assertApiError(
        sender.invokeScript(dAppV4, dAppV4Address, payment = payment, fee = smartMinFee + smartFee),
        AssertiveApiError(ScriptExecutionError.Id, "DApp self-payment is forbidden since V4", matchMessage = true)
      )
    }
  }

  test("V4: still can invoke itself without any payment") {
    sender.invokeScript(dAppV4, dAppV4Address, fee = smartMinFee + smartFee, waitForTx = true)
  }

  test("V4: can't send tokens to itself from a script") {
    for (
      args <- List(
        List(CONST_STRING("DCC").explicitGet()),
        List(CONST_STRING(asset1Id).explicitGet())
      )
    ) {
      assertApiError(
        sender.invokeScript(caller, dAppV4Address, Some("paySelf"), args),
        AssertiveApiError(ScriptExecutionError.Id, "Error while executing dApp: DApp self-transfer is forbidden since V4")
      )
    }
  }

  test("V3: still can invoke itself") {
    sender.invokeScript(dAppV3, dAppV3Address, fee = smartMinFee + smartFee, waitForTx = true)
    sender.invokeScript(
      dAppV3,
      dAppV3Address,
      payment = Seq(InvokeScriptTransaction.Payment(1, Dcc)),
      fee = smartMinFee + smartFee,
      waitForTx = true
    )
    sender.invokeScript(
      dAppV3,
      dAppV3Address,
      payment = Seq(InvokeScriptTransaction.Payment(1, asset1)),
      fee = smartMinFee + smartFee,
      waitForTx = true
    )
  }

  test("V3: still can pay itself") {
    sender.invokeScript(caller, dAppV3Address, Some("paySelf"), List(CONST_STRING("DCC").explicitGet()), waitForTx = true)
    sender.invokeScript(caller, dAppV3Address, Some("paySelf"), List(CONST_STRING(asset1Id).explicitGet()), waitForTx = true)
  }

}
