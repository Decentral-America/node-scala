package com.decentralchain.it.sync

import java.util.concurrent.ThreadLocalRandom
import scala.util.Try

import com.typesafe.config.{Config, ConfigFactory}
import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.{BaseFunSuite, Node}
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.api.{AsyncHttpApi, TransactionInfo}
import com.decentralchain.lang.v1.estimator.ScriptEstimatorV1
import com.decentralchain.transaction.{TxVersion, utils}
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.smart.script.ScriptCompiler
import com.decentralchain.transaction.transfer.TransferTransaction

class UtxSuite extends BaseFunSuite {
  private var whitelistedAccount: KeyPair     = scala.compiletime.uninitialized
  private var whitelistedDAppAccount: KeyPair = scala.compiletime.uninitialized

  private val ENOUGH_FEE = 5000000
  private val AMOUNT     = ENOUGH_FEE * 10

  test("Invalid transaction should be removed from utx") {
    val account = UtxSuite.createAccount

    val transferToAccount = TransferTransaction
      .selfSigned(1.toByte, miner.keyPair, account.toAddress, Dcc, AMOUNT, Dcc, ENOUGH_FEE, ByteStr.empty, System.currentTimeMillis())
      .explicitGet()

    miner.signedBroadcast(transferToAccount.json())

    nodes.waitForHeightAriseAndTxPresent(transferToAccount.id().toString)

    val firstTransfer = TransferTransaction
      .selfSigned(
        1.toByte,
        account,
        miner.keyPair.toAddress,
        Dcc,
        AMOUNT - ENOUGH_FEE,
        Dcc,
        ENOUGH_FEE,
        ByteStr.empty,
        System.currentTimeMillis()
      )
      .explicitGet()

    val secondTransfer = TransferTransaction
      .selfSigned(
        1.toByte,
        account,
        notMiner.keyPair.toAddress,
        Dcc,
        AMOUNT - ENOUGH_FEE,
        Dcc,
        ENOUGH_FEE,
        ByteStr.empty,
        System.currentTimeMillis()
      )
      .explicitGet()

    val tx2Id = notMiner.signedBroadcast(secondTransfer.json()).id
    val tx1Id = miner.signedBroadcast(firstTransfer.json()).id

    nodes.waitFor("empty utx")(_.utxSize)(_.forall(_ == 0))

    val exactlyOneTxInBlockchain =
      txInBlockchain(tx1Id, nodes) ^ txInBlockchain(tx2Id, nodes)

    assert(exactlyOneTxInBlockchain, "Only one tx should be in blockchain")
  }

  test("Whitelisted transactions should be mined first of all") {
    val minTransferFee  = 100000L
    val minInvokeFee    = 500000L
    val minSetScriptFee = 100000000L
    val higherFee       = minInvokeFee * 2

    val invokeAccount = UtxSuite.createAccount

    def time: Long = System.currentTimeMillis()

    val whitelistedAccountTransfer =
      TransferTransaction
        .selfSigned(
          TxVersion.V1,
          miner.keyPair,
          whitelistedAccount.toAddress,
          Dcc,
          5 * minTransferFee + 5 + (1 to 5).sum,
          Dcc,
          minTransferFee,
          ByteStr.empty,
          time
        )
        .explicitGet()
    val whitelistedDAppAccountTransfer =
      TransferTransaction
        .selfSigned(
          TxVersion.V1,
          miner.keyPair,
          whitelistedDAppAccount.toAddress,
          Dcc,
          minSetScriptFee,
          Dcc,
          minTransferFee,
          ByteStr.empty,
          time
        )
        .explicitGet()
    val invokeAccountTransfer = TransferTransaction
      .selfSigned(
        TxVersion.V1,
        miner.keyPair,
        invokeAccount.toAddress,
        Dcc,
        5 * minInvokeFee + (1 to 5).sum,
        Dcc,
        minTransferFee,
        ByteStr.empty,
        time
      )
      .explicitGet()

    Seq(whitelistedAccountTransfer, whitelistedDAppAccountTransfer, invokeAccountTransfer)
      .map(tx => miner.signedBroadcast(tx.json()).id)
      .foreach(nodes.waitForTransaction)

    val scriptText =
      """
        |{-# STDLIB_VERSION 3 #-}
        |{-# CONTENT_TYPE DAPP #-}
        |{-# SCRIPT_TYPE ACCOUNT #-}
        |@Callable(i)
        |func default() = { WriteSet([DataEntry("0", true)]) }
        |""".stripMargin
    val script    = ScriptCompiler.compile(scriptText, ScriptEstimatorV1).explicitGet()._1
    val setScript = SetScriptTransaction.selfSigned(TxVersion.V1, whitelistedDAppAccount, Some(script), minSetScriptFee, time).explicitGet()
    miner.signedBroadcast(setScript.json())
    nodes.waitForHeightAriseAndTxPresent(setScript.id().toString)

    val txs = (1 to 10).map { _ =>
      TransferTransaction
        .selfSigned(TxVersion.V1, miner.keyPair, UtxSuite.createAccount.toAddress, Dcc, 1L, Dcc, higherFee, ByteStr.empty, time)
        .explicitGet()
    }

    val whitelistedTxs = {
      val bySender = (1 to 5).map { i =>
        TransferTransaction
          .selfSigned(TxVersion.V1, whitelistedAccount, UtxSuite.createAccount.toAddress, Dcc, 1L, Dcc, minTransferFee + i, ByteStr.empty, time)
          .explicitGet()
      }
      val byDApp = (1 to 5).map { i =>
        utils.Signed.invokeScript(TxVersion.V1, invokeAccount, whitelistedDAppAccount.toAddress, None, Seq.empty, minInvokeFee + i, Dcc, time)
      }
      bySender ++ byDApp
    }

    val startHeight = nodes.waitForHeightArise()
    new scala.util.Random(ThreadLocalRandom.current()).shuffle(txs ++ whitelistedTxs).map(_.json()).foreach(AsyncHttpApi.NodeAsyncHttpApi(miner).signedBroadcast)
    miner.waitForEmptyUtx()
    val endHeight = miner.height

    miner.blockSeq(startHeight, endHeight).flatMap(_.transactions).map(_.id).take(10) should contain theSameElementsAs whitelistedTxs.map(
      _.id().toString
    )
  }

  def txInBlockchain(txId: String, nodes: Seq[Node]): Boolean = {
    nodes.forall { node =>
      Try(node.transactionInfo[TransactionInfo](txId)).isSuccess
    }
  }

  override protected def nodeConfigs: Seq[Config] = {
    import UtxSuite.*
    import com.decentralchain.it.NodeConfigs.*

    whitelistedAccount = createAccount
    whitelistedDAppAccount = createAccount

    val whitelist = Seq(whitelistedAccount, whitelistedDAppAccount).map(_.toAddress.toString)

    val minerConfig    = ConfigFactory.parseString(UtxSuite.minerConfigPredef(whitelist))
    val notMinerConfig = ConfigFactory.parseString(UtxSuite.notMinerConfigPredef(whitelist))

    Seq(
      minerConfig.withFallback(Default.head),
      notMinerConfig.withFallback(Default(1))
    )
  }
}

object UtxSuite {
  private def createAccount = {
    val seed = Array.fill(32)(-1: Byte)
    ThreadLocalRandom.current().nextBytes(seed)
    KeyPair(seed)
  }

  private def minerConfigPredef(whitelist: Seq[String]) =
    s"""
       |dcc {
       |  synchronization.synchronization-timeout = 10s
       |  utx {
       |    max-size = 5000
       |    fast-lane-addresses = [${whitelist.mkString(",")}]
       |  }
       |  blockchain.custom.functionality {
       |    pre-activated-features.1 = 0
       |    generation-balance-depth-from-50-to-1000-after-height = 100
       |  }
       |  miner.quorum = 0
       |  miner.max-transactions-in-micro-block = 1
       |}""".stripMargin

  private def notMinerConfigPredef(whitelist: Seq[String]) =
    s"""
       |dcc {
       |  synchronization.synchronization-timeout = 10s
       |  utx {
       |    max-size = 5000
       |    fast-lane-addresses = [${whitelist.mkString(",")}]
       |  }
       |  blockchain.custom.functionality {
       |    pre-activated-features.1 = 0
       |    generation-balance-depth-from-50-to-1000-after-height = 100
       |  }
       |  miner.enable = no
       |}""".stripMargin
}
