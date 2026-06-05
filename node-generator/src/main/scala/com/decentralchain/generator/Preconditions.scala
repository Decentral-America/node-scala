package com.decentralchain.generator

import com.google.common.primitives.{Bytes, Ints}
import com.decentralchain.account.{Address, KeyPair, SeedKeyPair}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.explicitGet
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.assets.IssueTransaction
import com.decentralchain.transaction.lease.LeaseTransaction
import com.decentralchain.transaction.transfer.TransferTransaction
import com.decentralchain.transaction.{Transaction, TxVersion}
import com.decentralchain.utils.Time
import pureconfig.ConfigReader

import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import scala.util.Try

object Preconditions {
  private val Fee = 1500000L

  private def rng = ThreadLocalRandom.current()

  private def randomString(n: Int): String = {
    val alpha = "abcdefghijklmnopqrstuvwxyz0123456789"
    (0 until n).map(_ => alpha.charAt(rng.nextInt(alpha.length))).mkString
  }

  given ConfigReader[KeyPair] =
    ConfigReader[String].map(s =>
      KeyPair(com.decentralchain.crypto.secureHash(Bytes.concat(Ints.toByteArray(0), s.getBytes(StandardCharsets.UTF_8))))
    )

  given ConfigReader[Address] = ConfigReader.fromStringTry(str => Try(Address.fromString(str).explicitGet()))

  final case class PGenSettings(faucet: KeyPair, balance: Long, leasesCount: Int, assetsCount: Int) derives ConfigReader

  final case class UniverseHolder(
      issuedAssets: List[IssueTransaction] = Nil,
      leases: List[LeaseTransaction] = Nil
  )

  def mk(
      settings: PGenSettings,
      accounts: Seq[SeedKeyPair],
      time: Time
  ): (UniverseHolder, List[Transaction], List[Transaction]) = {
    val transfers = accounts.map { account =>
      // val acc = GeneratorSettings.toKeyPair(accountSeed)
      TransferTransaction
        .selfSigned(2.toByte, settings.faucet, account.toAddress, Dcc, settings.balance, Dcc, Fee, ByteStr.empty, time.correctedTime())
        .explicitGet()
    }.toList

    val issuedAssets = (1 to settings.assetsCount)
      .map(_ =>
        IssueTransaction
          .selfSigned(
            TxVersion.V3,
            accounts(rng.nextInt(accounts.size)),
            UUID.randomUUID().toString.take(8),
            randomString(100),
            10_000_000_000L,
            rng.nextLong(9).toByte,
            true,
            None,
            100000000,
            time.correctedTime()
          )
          .explicitGet()
      )
      .toList

    val leaseTxs = (1 to settings.leasesCount).map { _ =>
      val rndAccount = rng.nextInt(accounts.size - 1)

      LeaseTransaction
        .selfSigned(
          TxVersion.V3,
          accounts(rndAccount),
          GeneratorSettings.toKeyPair(randomString(10)).toAddress,
          1 + rng.nextInt(1000),
          Fee,
          time.correctedTime()
        )
        .explicitGet()
    }.toList

    val transferAssets = issuedAssets.flatMap(issuedAsset =>
      val issuer  = accounts.find(_.publicKey == issuedAsset.sender).get
      val balance = issuedAsset.quantity.value / accounts.size
      accounts.map { acc =>
        TransferTransaction
          .selfSigned(
            TxVersion.V3,
            issuer,
            acc.toAddress,
            IssuedAsset(issuedAsset.assetId),
            balance,
            Dcc,
            Fee,
            ByteStr.empty,
            time.correctedTime()
          )
          .explicitGet()
      }
    )
    val holder = UniverseHolder(issuedAssets, leaseTxs)
    (holder, transfers ++ issuedAssets, transferAssets ++ leaseTxs)
  }

}
