package com.decentralchain.it.sync.smartcontract
import com.typesafe.config.Config
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.it.NodeConfigs
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.transactions.BaseTransactionSuite
import com.decentralchain.lang.v1.estimator.ScriptEstimatorV1
import com.decentralchain.state.Height
import com.decentralchain.transaction.smart.script.ScriptCompiler
import org.scalatest.CancelAfterFailure

class UtilsEstimatorToggleSuite extends BaseTransactionSuite with CancelAfterFailure {
  val estimatorV2ActivationHeight = Height(5)
  val estimatorV3ActivationHeight = Height(8)

  import NodeConfigs.*
  override protected def nodeConfigs: Seq[Config] = Seq(
    Miners(5)
      .quorum(0)
      .overrides("dcc.blockchain.custom.functionality.min-block-time = 2s")
      .preactivatedFeatures(
        (BlockchainFeatures.BlockReward, estimatorV2ActivationHeight),
        (BlockchainFeatures.BlockV5, estimatorV3ActivationHeight)
      )
  )

  val differentlyEstimatedScript: String =
    """
      | {-# STDLIB_VERSION 3 #-}
      | {-# CONTENT_TYPE EXPRESSION #-}
      |
      | let me = addressFromStringValue("")
      | func get() = getStringValue(me, "")
      | get() == get()
    """.stripMargin

  val v1Estimation = 467
  val v2Estimation = 342
  val v3Estimation = 330

  test("check estimations") {
    val compiledScript =
      ScriptCompiler
        .compile(differentlyEstimatedScript, ScriptEstimatorV1)
        .explicitGet()
        ._1
        .bytes()
        .base64

    sender.scriptEstimate(compiledScript).complexity shouldBe v1Estimation
    sender.waitForHeight(estimatorV2ActivationHeight)
    sender.scriptEstimate(compiledScript).complexity shouldBe v2Estimation
    sender.waitForHeight(estimatorV3ActivationHeight)
    sender.scriptEstimate(compiledScript).complexity shouldBe v3Estimation
  }
}
