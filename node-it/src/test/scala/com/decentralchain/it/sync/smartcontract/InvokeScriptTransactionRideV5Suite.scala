package com.decentralchain.it.sync.smartcontract

import com.typesafe.config.Config
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.it.NodeConfigs
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.sync.*
import com.decentralchain.it.sync.smartcontract.RideV4ActivationSuite.*
import com.decentralchain.it.transactions.BaseTransactionSuite
import com.decentralchain.state.Height
import com.decentralchain.state.diffs.FeeValidation.InvokeExtraFeePerStep
import com.decentralchain.test.*
import com.decentralchain.transaction.TxVersion
import com.decentralchain.transaction.transfer.MassTransferTransaction.Transfer
import org.scalatest.CancelAfterFailure

class InvokeScriptTransactionRideV5Suite extends BaseTransactionSuite with CancelAfterFailure {

  private lazy val dAppV3PK    = sender.createKeyPair()
  private lazy val dAppV4PK    = sender.createKeyPair()
  private lazy val dAppV5PK    = sender.createKeyPair()
  private lazy val callerPK    = firstKeyPair
  private lazy val dAppV3      = dAppV3PK.toAddress.toString
  private lazy val dAppV4      = dAppV4PK.toAddress.toString
  private lazy val dAppV5      = dAppV5PK.toAddress.toString
  private lazy val dAppAliasV3 = "dapp.v3"
  private lazy val dAppAliasV4 = "dapp.v4"
  private lazy val dAppAliasV5 = "dapp.v5"

  private def alias(name: String): String = s"alias:I:$name"

  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs
      .Builder(NodeConfigs.Default, 1, Seq.empty)
      .overrideBase(_.quorum(0))
      .overrideBase(
        _.preactivatedFeatures(
          (BlockchainFeatures.Ride4DApps.id, Height(0)),
          (BlockchainFeatures.BlockV5.id, Height(0)),
          (BlockchainFeatures.SynchronousCalls.id, Height(0)),
          // SC-695 (feature id 30): this suite is the "post-activation" side of the pairing --
          // it asserts the NEW version-gating/fee behavior. The "pre-activation, byte-identical
          // to today" side of the pairing is covered by fast, deterministic node-tests in
          // InvokeVersionGatingTest (node/tests/.../state/diffs/ci/InvokeVersionGatingTest.scala),
          // not duplicated here as a second node-it suite.
          (BlockchainFeatures.InvokeVersionGating.id, Height(0))
        )
      )
      .withDefault(1)
      .buildNonConflicting()

  protected override def beforeAll(): Unit = {
    super.beforeAll()

    val scriptV3 =
      """
        |{-# STDLIB_VERSION 3 #-}
        |{-# CONTENT_TYPE DAPP #-}
        |{-# SCRIPT_TYPE ACCOUNT #-}
        |
        |@Callable(inv)
        |func default() = WriteSet([])
        |""".stripMargin

    val scriptV4 =
      """
        |{-# STDLIB_VERSION 4 #-}
        |{-# CONTENT_TYPE DAPP #-}
        |{-# SCRIPT_TYPE ACCOUNT #-}
        |
        |@Callable(inv)
        |func default() = nil
        |""".stripMargin

    val scriptV5 =
      """
        |{-# STDLIB_VERSION 5 #-}
        |{-# CONTENT_TYPE DAPP #-}
        |{-# SCRIPT_TYPE ACCOUNT #-}
        |
        |@Callable(inv)
        |func default() = nil
        |""".stripMargin

    sender.massTransfer(
      callerPK,
      List(
        Transfer(dAppV3, 10.dcc),
        Transfer(dAppV4, 10.dcc),
        Transfer(dAppV5, 10.dcc)
      ),
      1.dcc,
      waitForTx = true
    )

    sender.createAlias(dAppV3PK, dAppAliasV3, fee = 1.dcc)
    sender.createAlias(dAppV4PK, dAppAliasV4, fee = 1.dcc)
    sender.createAlias(dAppV5PK, dAppAliasV5, fee = 1.dcc)

    sender.setScript(dAppV3PK, Some(scriptV3.compiled), setScriptFee + 100)
    sender.setScript(dAppV4PK, Some(scriptV4.compiled), setScriptFee + 10)
    sender.setScript(dAppV5PK, Some(scriptV5.compiled), setScriptFee, waitForTx = true)
  }

  // SC-695 (feature id 30, BlockchainFeatures.InvokeVersionGating, pre-activated above).
  // Production error message: ApiError.StateCheckFailed prefixes every rejection with
  // "State check failed. Reason: " (see node/src/main/scala/com/decentralchain/api/http/ApiError.scala)
  // followed by the specific reason -- InvokeVersionGating.rejectionMessage in this case. The
  // ignored version of this test asserted the placeholder `"State check failed"` exactly, which
  // was never real production copy (flagged, not shipped -- see feature-30-sc695-spec.md's
  // non-goal #4); this corrected version matches the real message shape instead.
  private def assertVersionGatingRejected(dapp: String, version: TxVersion): Unit =
    assertApiError(sender.invokeScript(callerPK, dapp, version = version)) { error =>
      error.statusCode shouldBe 400
      error.message should include("State check failed")
      error.message should include("InvokeScriptTransaction")
    }

  test("Can't invoke Ride V5 DApp via InvokeScriptTx V1") {
    assertVersionGatingRejected(dAppV5, TxVersion.V1)
    assertVersionGatingRejected(alias(dAppAliasV5), TxVersion.V1)
  }

  test("Can't invoke Ride V5 DApp via InvokeScriptTx V2") {
    assertVersionGatingRejected(dAppV5, TxVersion.V2)
    assertVersionGatingRejected(alias(dAppAliasV5), TxVersion.V2)
  }

  test("Can invoke Ride V5 DApp via InvokeScriptTx V3") {
    sender.invokeScript(callerPK, dAppV5, version = TxVersion.V3, waitForTx = true)
    sender.invokeScript(callerPK, alias(dAppAliasV5), version = TxVersion.V3, waitForTx = true)
  }

  // Corrected resolution of the original test-authoring gap: the ignored tests were titled
  // "...if extraFeePerStep is specified" but their bodies never passed any such value (see
  // feature-30-sc695-spec.md's problem-statement section for the full discrepancy analysis).
  // This implementation realizes extraFeePerStep as a static required fee bump (see
  // FeeValidation.InvokeExtraFeePerStep / InvokeVersionGating.extraFeeSteps), reusing the
  // existing `fee` parameter rather than adding a new wire field -- so "specifying
  // extraFeePerStep" is expressed here by paying `smartMinFee + InvokeExtraFeePerStep`, and
  // "not specifying it" by paying the plain `smartMinFee` default (which must now be rejected
  // as insufficient, not silently accepted).
  test("Can't invoke Ride V3 DApp via InvokeScriptTx V3 without the required extraFeePerStep") {
    assertApiError(sender.invokeScript(callerPK, dAppV3, version = TxVersion.V3)) { error =>
      error.statusCode shouldBe 400
      error.message should include("State check failed")
      error.message should include("extra fee")
    }
    assertApiError(sender.invokeScript(callerPK, alias(dAppAliasV3), version = TxVersion.V3)) { error =>
      error.statusCode shouldBe 400
      error.message should include("State check failed")
      error.message should include("extra fee")
    }
  }

  test("Can invoke Ride V3 DApp via InvokeScriptTx V3 if extraFeePerStep is specified") {
    sender.invokeScript(callerPK, dAppV3, fee = smartMinFee + InvokeExtraFeePerStep, version = TxVersion.V3, waitForTx = true)
    sender.invokeScript(callerPK, alias(dAppAliasV3), fee = smartMinFee + InvokeExtraFeePerStep, version = TxVersion.V3, waitForTx = true)
  }

  test("Can't invoke Ride V4 DApp via InvokeScriptTx V3 without the required extraFeePerStep") {
    assertApiError(sender.invokeScript(callerPK, dAppV4, version = TxVersion.V3)) { error =>
      error.statusCode shouldBe 400
      error.message should include("State check failed")
      error.message should include("extra fee")
    }
    assertApiError(sender.invokeScript(callerPK, alias(dAppAliasV4), version = TxVersion.V3)) { error =>
      error.statusCode shouldBe 400
      error.message should include("State check failed")
      error.message should include("extra fee")
    }
  }

  test("Can invoke Ride V4 DApp via InvokeScriptTx V3 if extraFeePerStep is specified") {
    sender.invokeScript(callerPK, dAppV4, fee = smartMinFee + InvokeExtraFeePerStep, version = TxVersion.V3, waitForTx = true)
    sender.invokeScript(callerPK, alias(dAppAliasV4), fee = smartMinFee + InvokeExtraFeePerStep, version = TxVersion.V3, waitForTx = true)
  }

}
