package com.decentralchain.it.sync.smartcontract

import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.api.TransactionInfo
import com.decentralchain.it.sync.{minFee, setScriptFee, transferAmount}
import com.decentralchain.it.transactions.BaseTransactionSuite
import com.decentralchain.lang.v1.estimator.v2.ScriptEstimatorV2
import com.decentralchain.test.*
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.{Proofs, TxPositiveAmount}
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.smart.script.ScriptCompiler
import com.decentralchain.transaction.transfer.TransferTransaction
import org.scalatest.CancelAfterFailure

class SetScriptTransactionSuite extends BaseTransactionSuite with CancelAfterFailure {
  private lazy val fourthAddress: KeyPair = sender.createKeyPair()
  private lazy val fifthAddress: KeyPair  = sender.createKeyPair()

  private def acc0      = firstKeyPair
  private def acc1      = secondKeyPair
  private def acc2      = thirdKeyPair
  private lazy val acc3 = fourthAddress
  private lazy val acc4 = fifthAddress

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    sender.transfer(acc0, acc4.toAddress.toString, 10.dcc, waitForTx = true)
  }

  test("set acc0 as 2of2 multisig") {
    for (v <- setScrTxSupportedVersions) {
      val contract   = if (v < 2) acc0 else acc4
      val scriptText =
        s"""
        match tx {
          case _: Transaction => {
            let A = base58'${acc1.publicKey}'
            let B = base58'${acc2.publicKey}'
            let AC = sigVerify(tx.bodyBytes,tx.proofs[0],A)
            let BC = sigVerify(tx.bodyBytes,tx.proofs[1],B)
            AC && BC
          }
          case _ => false
        }
      """.stripMargin

      val (contractBalance, contractEffBalance) = sender.accountBalances(contract.toAddress.toString)
      val script                                = ScriptCompiler.compile(scriptText, ScriptEstimatorV2).explicitGet()._1.bytes().base64
      val setScriptId                           = sender.setScript(contract, Some(script), setScriptFee, version = v).id

      nodes.waitForHeightAriseAndTxPresent(setScriptId)

      val acc0ScriptInfo = sender.addressScriptInfo(contract.toAddress.toString)

      acc0ScriptInfo.script.isEmpty shouldBe false
      acc0ScriptInfo.scriptText.isEmpty shouldBe false

      acc0ScriptInfo.script.get.startsWith("base64:") shouldBe true

      sender.transactionInfo[TransactionInfo](setScriptId).script.get.startsWith("base64:") shouldBe true
      sender.assertBalances(contract.toAddress.toString, contractBalance - setScriptFee, contractEffBalance - setScriptFee)
    }
  }

  test("can't send from contract using old pk") {
    for (v <- setScrTxSupportedVersions) {
      val contract                              = if (v < 2) acc0 else acc4
      val (contractBalance, contractEffBalance) = sender.accountBalances(contract.toAddress.toString)
      val (acc3Balance, acc3EffBalance)         = sender.accountBalances(acc3.toAddress.toString)
      assertApiErrorRaised(
        sender.transfer(
          contract,
          recipient = acc3.toAddress.toString,
          assetId = None,
          amount = transferAmount,
          fee = minFee + 0.00001.dcc + 0.00002.dcc
        )
      )
      sender.assertBalances(contract.toAddress.toString, contractBalance, contractEffBalance)
      sender.assertBalances(acc3.toAddress.toString, acc3Balance, acc3EffBalance)
    }
  }

  test("can send from acc0 using multisig of acc1 and acc2") {
    for (v <- setScrTxSupportedVersions) {
      val contract                              = if (v < 2) acc0 else acc4
      val (contractBalance, contractEffBalance) = sender.accountBalances(contract.toAddress.toString)
      val (acc3Balance, acc3EffBalance)         = sender.accountBalances(acc3.toAddress.toString)
      val unsigned                              =
        TransferTransaction(
          version = 2.toByte,
          sender = contract.publicKey,
          recipient = acc3.toAddress,
          assetId = Dcc,
          amount = TxPositiveAmount.unsafeFrom(1000),
          feeAssetId = Dcc,
          fee = TxPositiveAmount.unsafeFrom(minFee + 0.004.dcc),
          attachment = ByteStr.empty,
          timestamp = System.currentTimeMillis(),
          proofs = Proofs.empty,
          acc3.toAddress.chainId
        )
      val sig1         = crypto.sign(acc1.privateKey, unsigned.bodyBytes())
      val sig2         = crypto.sign(acc2.privateKey, unsigned.bodyBytes())
      val signed       = unsigned.copy(proofs = Proofs(sig1, sig2))
      val transferTxId = sender.signedBroadcast(signed.json()).id

      nodes.waitForHeightAriseAndTxPresent(transferTxId)

      sender.assertBalances(
        contract.toAddress.toString,
        contractBalance - 1000 - minFee - 0.004.dcc,
        contractEffBalance - 1000 - minFee - 0.004.dcc
      )
      sender.assertBalances(acc3.toAddress.toString, acc3Balance + 1000, acc3EffBalance + 1000)
    }
  }

  test("can clear script at contract") {
    for (v <- setScrTxSupportedVersions) {
      val contract                              = if (v < 2) acc0 else acc4
      val (contractBalance, contractEffBalance) = sender.accountBalances(contract.toAddress.toString)
      val unsigned                              = SetScriptTransaction
        .create(
          version = v,
          sender = contract.publicKey,
          script = None,
          fee = setScriptFee + 0.004.dcc,
          timestamp = System.currentTimeMillis(),
          proofs = Proofs.empty
        )
        .explicitGet()
      val sig1 = crypto.sign(acc1.privateKey, unsigned.bodyBytes())
      val sig2 = crypto.sign(acc2.privateKey, unsigned.bodyBytes())

      val signed = unsigned.copy(version = v, proofs = Proofs(Seq(sig1, sig2)))

      val removeScriptId = sender
        .signedBroadcast(signed.json())
        .id

      nodes.waitForHeightAriseAndTxPresent(removeScriptId)

      sender.transactionInfo[TransactionInfo](removeScriptId).script shouldBe None
      sender.addressScriptInfo(contract.toAddress.toString).script shouldBe None
      sender.addressScriptInfo(contract.toAddress.toString).scriptText shouldBe None
      sender.assertBalances(
        contract.toAddress.toString,
        contractBalance - setScriptFee - 0.004.dcc,
        contractEffBalance - setScriptFee - 0.004.dcc
      )
    }
  }

  test("can send using old pk of contract") {
    for (v <- setScrTxSupportedVersions) {
      val contract                              = if (v < 2) acc0 else acc4
      val (contractBalance, contractEffBalance) = sender.accountBalances(contract.toAddress.toString)
      val (acc3Balance, acc3EffBalance)         = sender.accountBalances(acc3.toAddress.toString)
      val transferTxId                          = sender
        .transfer(
          contract,
          recipient = acc3.toAddress.toString,
          assetId = None,
          amount = 1000,
          fee = minFee,
          version = 2
        )
        .id

      nodes.waitForHeightAriseAndTxPresent(transferTxId)
      sender.assertBalances(contract.toAddress.toString, contractBalance - 1000 - minFee, contractEffBalance - 1000 - minFee)
      sender.assertBalances(acc3.toAddress.toString, acc3Balance + 1000, acc3EffBalance + 1000)
    }
  }
}
