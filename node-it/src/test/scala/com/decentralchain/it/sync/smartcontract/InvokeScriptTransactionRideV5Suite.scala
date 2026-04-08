package com.decentralchain.it.sync.smartcontract

import com.typesafe.config.Config
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.it.NodeConfigs
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.sync.*
import com.decentralchain.it.sync.smartcontract.RideV4ActivationSuite.*
import com.decentralchain.it.transactions.BaseTransactionSuite
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

  import NodeConfigs.*
  override protected def nodeConfigs: Seq[Config] = Seq(
    BiggestMiner
      .quorum(0)
      .preactivatedFeatures(
        BlockchainFeatures.Ride4DApps,
        BlockchainFeatures.BlockV5,
        BlockchainFeatures.SynchronousCalls
      )
  )

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

  // NOTE: Disabled pending SC-695 (upstream ticket)
  ignore("Can't invoke Ride V5 DApp via InvokeScriptTx V1") {
    assertApiError(
      sender.invokeScript(callerPK, dAppV5, version = TxVersion.V1)
    ) { error =>
      error.statusCode shouldBe 400
      error.message shouldBe "State check failed" // NOTE: Detailed error message to be implemented in future
    }

    assertApiError(
      sender.invokeScript(callerPK, alias(dAppAliasV5), version = TxVersion.V1)
    ) { error =>
      error.statusCode shouldBe 400
      error.message shouldBe "State check failed" // NOTE: Detailed error message to be implemented in future
    }
  }

  // NOTE: Disabled pending SC-695 (upstream ticket)
  ignore("Can't invoke Ride V5 DApp via InvokeScriptTx V2") {
    assertApiError(
      sender.invokeScript(callerPK, dAppV5, version = TxVersion.V2)
    ) { error =>
      error.statusCode shouldBe 400
      error.message shouldBe "State check failed" // NOTE: Detailed error message to be implemented in future
    }

    assertApiError(
      sender.invokeScript(callerPK, alias(dAppAliasV5), version = TxVersion.V2)
    ) { error =>
      error.statusCode shouldBe 400
      error.message shouldBe "State check failed" // NOTE: Detailed error message to be implemented in future
    }
  }

  // NOTE: Disabled pending SC-695 (upstream ticket)
  ignore("Can invoke Ride V5 DApp via InvokeScriptTx V3") {
    sender.invokeScript(callerPK, dAppV5, version = TxVersion.V3, waitForTx = true)
    sender.invokeScript(callerPK, alias(dAppAliasV5), version = TxVersion.V3, waitForTx = true)
  }

  // NOTE: Disabled pending SC-695 (upstream ticket)
  ignore("Can't invoke Ride V3 DApp via InvokeScriptTx V3 if extraFeePerStep is specified") {
    // NOTE: extraFeePerStep calculation to be added in future
    assertApiError(
      sender.invokeScript(callerPK, dAppV3, version = TxVersion.V3)
    ) { error =>
      error.statusCode shouldBe 400
      error.message shouldBe "State check failed" // NOTE: Detailed error message to be implemented in future
    }

    assertApiError(
      sender.invokeScript(callerPK, alias(dAppAliasV3), version = TxVersion.V3)
    ) { error =>
      error.statusCode shouldBe 400
      error.message shouldBe "State check failed" // NOTE: Detailed error message to be implemented in future
    }
  }

  // NOTE: Disabled pending SC-695 (upstream ticket)
  ignore("Can't invoke Ride V4 DApp via InvokeScriptTx V3 if extraFeePerStep is specified") {
    // NOTE: extraFeePerStep calculation to be added in future
    assertApiError(
      sender.invokeScript(callerPK, dAppV4, version = TxVersion.V3)
    ) { error =>
      error.statusCode shouldBe 400
      error.message shouldBe "State check failed" // NOTE: Detailed error message to be implemented in future
    }

    assertApiError(
      sender.invokeScript(callerPK, alias(dAppAliasV4), version = TxVersion.V3)
    ) { error =>
      error.statusCode shouldBe 400
      error.message shouldBe "State check failed" // NOTE: Detailed error message to be implemented in future
    }
  }

}
