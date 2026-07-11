package com.decentralchain.generator

import cats.Show
import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.explicitGet
import com.decentralchain.generator.OracleTransactionGenerator.Settings
import com.decentralchain.generator.config.ConfigReaders
import com.decentralchain.generator.utils.Gen
import com.decentralchain.generator.utils.Implicits.DoubleExt
import com.decentralchain.lang.v1.estimator.ScriptEstimator
import com.decentralchain.state.*
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.transfer.TransferTransaction
import com.decentralchain.transaction.{DataTransaction, Transaction}
import pureconfig.ConfigReader

class OracleTransactionGenerator(settings: Settings, val accounts: Seq[KeyPair], estimator: ScriptEstimator) extends TransactionGenerator {
  override def next(): Iterator[Transaction] = generate(settings).iterator

  def generate(settings: Settings): Seq[Transaction] = {
    val oracle = accounts.last

    val scriptedAccount = accounts.head

    val script = Gen.oracleScript(oracle, settings.requiredData, estimator)

    val enoughFee = 0.005.dcc

    val setScript: Transaction =
      SetScriptTransaction
        .selfSigned(1.toByte, scriptedAccount, Some(script), enoughFee, timestamp = System.currentTimeMillis())
        .explicitGet()

    val setDataTx: Transaction = DataTransaction
      .selfSigned(1.toByte, oracle, settings.requiredData.toList, enoughFee, System.currentTimeMillis())
      .explicitGet()

    val now                             = System.currentTimeMillis()
    val transactions: List[Transaction] = (1 to settings.transactions).map { i =>
      TransferTransaction
        .selfSigned(2.toByte, scriptedAccount, oracle.toAddress, Dcc, 1.dcc, Dcc, enoughFee, ByteStr.empty, now + i)
        .explicitGet()
    }.toList

    setScript +: setDataTx +: transactions
  }
}

object OracleTransactionGenerator extends ConfigReaders {
  final case class Settings(transactions: Int, requiredData: Set[DataEntry[?]]) derives ConfigReader

  object Settings {
    implicit val toPrintable: Show[Settings] = { x =>
      s"Transactions: ${x.transactions}\n" +
        s"DataEntries: ${x.requiredData}\n"
    }
  }
}
