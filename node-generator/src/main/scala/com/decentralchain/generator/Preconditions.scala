package com.decentralchain.generator

import com.google.common.primitives.{Bytes, Ints}
import com.decentralchain.account.{Address, KeyPair, SeedKeyPair}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.explicitGet
import com.decentralchain.transaction.Asset.{IssuedAsset, Waves}
import com.decentralchain.transaction.assets.IssueTransaction
import com.decentralchain.transaction.lease.LeaseTransaction
import com.decentralchain.transaction.{Transaction, TxHelpers, TxVersion}
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
      TxHelpers.transfer(settings.faucet, account.toAddress, settings.balance, Waves, Fee, Waves, ByteStr.empty, time.correctedTime())
    }.toList

    val issuedAssets = (1 to settings.assetsCount)
      .map(_ =>
        TxHelpers.issue(
          accounts(Random.nextInt(accounts.size)),
          10_000_000_000L,
          Random.nextLong(9).toByte,
          UUID.randomUUID().toString.take(8),
          Random.nextString(100),
          100000000,
          None,
          true,
          time.correctedTime(),
          TxVersion.V3
        )
      )
      .toList

    val leaseTxs = (1 to settings.leasesCount).map { _ =>
      val rndAccount = rng.nextInt(accounts.size - 1)

      TxHelpers.lease(
        accounts(rndAccount),
        GeneratorSettings.toKeyPair(Random.nextString(10)).toAddress,
        1 + Random.nextInt(1000),
        Fee,
        time.correctedTime(),
        TxVersion.V3
      )
    }.toList

    val transferAssets = issuedAssets.flatMap(issuedAsset =>
      val issuer  = accounts.find(_.publicKey == issuedAsset.sender).get
      val balance = issuedAsset.quantity.value / accounts.size
      accounts.map { acc =>
        TxHelpers.transfer(
          issuer,
          acc.toAddress,
          balance,
          IssuedAsset(issuedAsset.assetId),
          Fee,
          Waves,
          ByteStr.empty,
          time.correctedTime(),
          TxVersion.V3
        )
      }
    )
    val holder = UniverseHolder(issuedAssets, leaseTxs)
    (holder, transfers ++ issuedAssets, transferAssets ++ leaseTxs)
  }

}
