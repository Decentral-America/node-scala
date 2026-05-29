package com.decentralchain.it

import com.decentralchain.account.{KeyPair, SeedKeyPair}
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.test.NumericExt
import com.decentralchain.lang.v1.estimator.v2.ScriptEstimatorV2
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.smart.script.ScriptCompiler
import com.decentralchain.transaction.transfer.*
import com.decentralchain.state.Height
import com.decentralchain.utils.ScorexLogging
import org.scalatest.*
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}

trait IntegrationSuiteWithThreeAddresses extends BaseSuite with ScalaFutures with IntegrationPatience with RecoverMethods with ScorexLogging {
  this: TestSuite & Nodes =>

  protected lazy val firstKeyPair: SeedKeyPair = SeedKeyPair("firstKeyPair".getBytes())
  protected lazy val firstAddress: String      = firstKeyPair.toAddress.toString

  protected lazy val secondKeyPair: KeyPair = SeedKeyPair("secondKeyPair".getBytes())
  protected lazy val secondAddress: String  = secondKeyPair.toAddress.toString

  protected lazy val thirdKeyPair: KeyPair = SeedKeyPair("thirdKeyPair".getBytes())
  protected lazy val thirdAddress: String  = thirdKeyPair.toAddress.toString

  abstract protected override def beforeAll(): Unit = {
    super.beforeAll()

    val defaultBalance: Long = 100.dcc

    def makeTransfers(accounts: Seq[KeyPair]): Seq[String] = accounts.map { acc =>
      sender.transfer(sender.keyPair, acc.toAddress.toString, defaultBalance, sender.fee(TransferTransaction.typeId)).id
    }

    def correctStartBalancesFuture(): Unit = {
      val accounts = Seq(firstKeyPair, secondKeyPair, thirdKeyPair)

      withClue("waitForTxsToReachAllNodes") {
        nodes.waitForHeight(Height(makeTransfers(accounts).map(ts => nodes.waitForTransaction(ts).height).max + 1))
      }
    }

    withClue("beforeAll") {
      correctStartBalancesFuture()
    }
  }

  def setContract(contractText: Option[String], acc: KeyPair): String = {
    val script = contractText.map { x =>
      val scriptText = x.stripMargin
      ScriptCompiler.compile(scriptText, ScriptEstimatorV2).explicitGet()._1
    }
    val setScriptTransaction = SetScriptTransaction
      .selfSigned(1.toByte, acc, script, 0.014.dcc, System.currentTimeMillis())
      .explicitGet()
    sender
      .signedBroadcast(setScriptTransaction.json(), waitForTx = true)
      .id
  }

  def setContracts(contracts: (Option[String], KeyPair)*): Unit = {
    contracts
      .map { case (src, acc) =>
        setContract(src, acc)
      }
      .foreach(id => sender.waitForTransaction(id))
    nodes.waitForHeightArise()
  }
}
