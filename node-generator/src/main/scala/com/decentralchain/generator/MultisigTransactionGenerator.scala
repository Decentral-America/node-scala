package com.decentralchain.generator

import cats.Show
import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.explicitGet
import com.decentralchain.crypto
import com.decentralchain.generator.utils.Gen
import com.decentralchain.generator.utils.Implicits.DoubleExt
import com.decentralchain.lang.script.Script
import com.decentralchain.lang.v1.estimator.ScriptEstimator
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.transfer.TransferTransaction
import com.decentralchain.transaction.{Proofs, Transaction, TxPositiveAmount}
import pureconfig.ConfigReader

import java.security.SecureRandom

class MultisigTransactionGenerator(settings: MultisigTransactionGenerator.Settings, val accounts: Seq[KeyPair], estimator: ScriptEstimator)
    extends TransactionGenerator {

  override def next(): Iterator[Transaction] = generate(settings).iterator

  private def generate(settings: MultisigTransactionGenerator.Settings): Seq[Transaction] = {

    val bank   = accounts.head
    val owners = Seq(createAccount(), accounts(1), createAccount(), accounts(2), createAccount(), accounts(3), createAccount(), createAccount())

    val enoughFee               = 0.005.dcc
    val totalAmountOnNewAccount = 1.dcc

    val script: Script = Gen.multiSigScript(owners, 3, estimator)

    val now = System.currentTimeMillis()
    val setScript =
      SetScriptTransaction.create(1.toByte, bank.publicKey, Some(script), enoughFee, now, Proofs.empty).map(_.signWith(bank.privateKey)).explicitGet()

    val res = Range(0, settings.transactions).map { i =>
      val tx = TransferTransaction(
        2.toByte,
        bank.publicKey,
        owners(1).toAddress,
        Dcc,
        TxPositiveAmount.unsafeFrom(totalAmountOnNewAccount - 2 * enoughFee - i),
        Dcc,
        TxPositiveAmount.unsafeFrom(enoughFee),
        ByteStr.empty,
        now + i,
        Proofs.empty,
        owners(1).toAddress.chainId
      )
      val signatures = owners.map(o => crypto.sign(o.privateKey, tx.bodyBytes()))
      tx.copy(proofs = Proofs(signatures))
    }

    println(System.currentTimeMillis())
    println(s"${res.length} tx generated")

    if (settings.firstRun) setScript +: res
    else res
  }

  private val secureRandom = new SecureRandom()

  private def createAccount() = {
    val seedBytes = Array.fill(32)(0: Byte)
    secureRandom.nextBytes(seedBytes)
    KeyPair(seedBytes)
  }
}

object MultisigTransactionGenerator {
  final case class Settings(transactions: Int, firstRun: Boolean) derives ConfigReader

  object Settings {
    implicit val toPrintable: Show[Settings] = { x =>
      s"""
         | transactions = ${x.transactions}
         | firstRun = ${x.firstRun}
      """.stripMargin
    }
  }
}
