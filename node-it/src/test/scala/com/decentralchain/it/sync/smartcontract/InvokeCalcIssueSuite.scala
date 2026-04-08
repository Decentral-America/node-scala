package com.decentralchain.it.sync.smartcontract

import com.typesafe.config.Config
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.it.NodeConfigs
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.sync.*
import com.decentralchain.it.transactions.BaseTransactionSuite
import com.decentralchain.it.util.*
import com.decentralchain.lang.v1.estimator.v3.ScriptEstimatorV3
import com.decentralchain.state.BinaryDataEntry
import com.decentralchain.transaction.smart.script.ScriptCompiler
import org.scalatest.*

class InvokeCalcIssueSuite extends BaseTransactionSuite with CancelAfterFailure with OptionValues {
  import InvokeCalcIssueSuite.*
  import NodeConfigs.*
  override protected def nodeConfigs: Seq[Config] = Seq(
    BiggestMiner.quorum(0).preactivatedFeatures(BlockchainFeatures.BlockV5)
  )

  private def smartAcc  = firstKeyPair
  private def callerAcc = secondKeyPair

  test("calculateAssetId should return right unique id for each invoke") {

    sender.setScript(
      smartAcc,
      Some(ScriptCompiler.compile(dAppV4, ScriptEstimatorV3.latest).explicitGet()._1.bytes().base64),
      fee = setScriptFee + smartFee,
      waitForTx = true
    )
    val smartAccAddress = smartAcc.toAddress.toString
    sender
      .invokeScript(
        callerAcc,
        smartAccAddress,
        Some("i"),
        args = List.empty,
        fee = invokeFee + issueFee, // dAppV4 contains 1 Issue action
        waitForTx = true
      )
    val assetId = sender.getDataByKey(smartAccAddress, "id").as[BinaryDataEntry].value.toString

    sender
      .invokeScript(
        callerAcc,
        smartAccAddress,
        Some("i"),
        args = List.empty,
        fee = invokeFee + issueFee, // dAppV4 contains 1 Issue action
        waitForTx = true
      )
    val secondAssetId = sender.getDataByKey(smartAccAddress, "id").as[BinaryDataEntry].value.toString

    sender.assetBalance(smartAccAddress, assetId).balance shouldBe 100
    sender.assetBalance(smartAccAddress, secondAssetId).balance shouldBe 100

    val assetDetails = sender.assetsDetails(assetId)
    assetDetails.decimals shouldBe decimals
    assetDetails.name shouldBe assetName
    assetDetails.reissuable shouldBe reissuable
    assetDetails.description shouldBe assetDescr
    assetDetails.minSponsoredAssetFee shouldBe None

  }
}

object InvokeCalcIssueSuite {

  val assetName  = "InvokeAsset"
  val assetDescr = "Invoke asset descr"
  val amount     = 100
  val decimals   = 0
  val reissuable = true

  private val dAppV4: String =
    s"""{-# STDLIB_VERSION 4 #-}
       |{-# CONTENT_TYPE DAPP #-}
       |
       |@Callable(i)
       |func i() = {
       |let issue = Issue("$assetName", "$assetDescr", $amount, $decimals, $reissuable, unit, 0)
       |let id = calculateAssetId(issue)
       |[issue,
       | BinaryEntry("id", id)]
       |}
       |
       |""".stripMargin
}
