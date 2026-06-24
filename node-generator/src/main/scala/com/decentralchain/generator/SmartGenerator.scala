package com.decentralchain.generator

import cats.Show
import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.explicitGet
import com.decentralchain.generator.utils.Gen
import com.decentralchain.generator.utils.Implicits.DoubleExt
import com.decentralchain.lang.script.Script
import com.decentralchain.lang.v1.estimator.ScriptEstimator
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.assets.exchange.{AssetPair, Order}
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.transfer.TransferTransaction
import com.decentralchain.transaction.{Asset, Proofs, Transaction, TxHelpers, TxVersion}
import pureconfig.ConfigReader

import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.*

class SmartGenerator(settings: SmartGenerator.Settings, val accounts: Seq[KeyPair], estimator: ScriptEstimator) extends TransactionGenerator {
  private def r                                   = ThreadLocalRandom.current
  private def randomFrom[T](c: Seq[T]): Option[T] = if (c.nonEmpty) Some(c(r.nextInt(c.size))) else None

  override def next(): Iterator[Transaction] = generate(settings).iterator

  private def generate(settings: SmartGenerator.Settings): Seq[Transaction] = {
    val bank = randomFrom(accounts).get

    val fee = 0.005.dcc

    val script: Script = Gen.script(settings.complexity, estimator)

    val setScripts = Range(0, settings.scripts) flatMap (_ =>
      accounts.map { i =>
        SetScriptTransaction
          .create(1.toByte, i.publicKey, Some(script), 1.dcc, System.currentTimeMillis(), Proofs.empty)
          .map(_.signWith(i.privateKey))
          .explicitGet()
      }
    )

    val now = System.currentTimeMillis()
    val txs = Range(0, settings.transfers).map { i =>
      TransferTransaction
        .create(2.toByte, bank.publicKey, bank.toAddress, Dcc, 1.dcc - 2 * fee, Dcc, fee, ByteStr.empty, now + i, Proofs.empty)
        .map(_.signWith(bank.privateKey))
        .explicitGet()
    }

    val extxs = Range(0, settings.exchange).map { i =>
      val ts = now + i

      val matcher         = randomFrom(accounts).get
      val seller          = randomFrom(accounts).get
      val buyer           = randomFrom(accounts).get
      val asset           = randomFrom(settings.assets.toSeq)
      val tradeAssetIssue = ByteStr.decodeBase58(asset.get).toOption
      val pair            = AssetPair(Dcc, Asset.fromCompatId(tradeAssetIssue))
      val sellOrder = Order.sell(TxVersion.V2, seller, matcher.publicKey, pair, 100000000L, 1, ts, ts + 30.days.toMillis, 0.003.dcc).explicitGet()
      val buyOrder  = Order.buy(TxVersion.V2, buyer, matcher.publicKey, pair, 100000000L, 1, ts, ts + 1.day.toMillis, 0.003.dcc).explicitGet()

      TxHelpers.exchange(buyOrder, sellOrder, matcher, 100000000, 1, 0.003.dcc, 0.003.dcc, 0.011.dcc, ts)
    }

    setScripts ++ txs ++ extxs
  }

}

object SmartGenerator {
  final case class Settings(scripts: Int, transfers: Int, complexity: Boolean, exchange: Int, assets: Set[String]) derives ConfigReader {
    require(scripts >= 0)
    require(transfers >= 0)
    require(exchange >= 0)
  }

  object Settings {
    implicit val toPrintable: Show[Settings] = { x =>
      import x.*
      s"""
         | set-scripts = $scripts
         | transfers = $transfers
         | complexity = $complexity
         | exchange = $exchange
         | assets = $assets
      """.stripMargin
    }
  }
}
