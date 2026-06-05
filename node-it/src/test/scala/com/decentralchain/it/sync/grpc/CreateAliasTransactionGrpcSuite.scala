package com.decentralchain.it.sync.grpc

import java.util.concurrent.ThreadLocalRandom

import scala.util.Try

import com.decentralchain.account.AddressScheme
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.NTPTime
import com.decentralchain.it.api.SyncGrpcApi.*
import com.decentralchain.it.sync.{aliasTxSupportedVersions, minFee, transferAmount}
import io.decentralchain.protobuf.transaction.{PBRecipients, Recipient}
import com.decentralchain.test.*
import io.grpc.Status.Code
import org.scalatest.prop.TableDrivenPropertyChecks

class CreateAliasTransactionGrpcSuite extends GrpcBaseTransactionSuite with NTPTime with TableDrivenPropertyChecks {

  val (aliasCreator, aliasCreatorAddr) = (firstAcc, firstAddress)
  test("Able to send money to an alias") {
    for (v <- aliasTxSupportedVersions) {
      val alias             = randomAlias()
      val creatorBalance    = sender.dccBalance(aliasCreatorAddr).available
      val creatorEffBalance = sender.dccBalance(aliasCreatorAddr).effective

      sender.broadcastCreateAlias(aliasCreator, alias, minFee, version = v, waitForTx = true)

      sender.dccBalance(aliasCreatorAddr).available shouldBe creatorBalance - minFee
      sender.dccBalance(aliasCreatorAddr).effective shouldBe creatorEffBalance - minFee

      sender.resolveAlias(alias) shouldBe PBRecipients.toAddress(aliasCreatorAddr.toByteArray, AddressScheme.current.chainId).explicitGet()

      sender.broadcastTransfer(aliasCreator, Recipient().withAlias(alias), transferAmount, minFee, waitForTx = true)

      sender.dccBalance(aliasCreatorAddr).available shouldBe creatorBalance - 2 * minFee
      sender.dccBalance(aliasCreatorAddr).effective shouldBe creatorEffBalance - 2 * minFee
    }
  }

  test("Not able to create same aliases to same address") {
    for (v <- aliasTxSupportedVersions) {
      val alias             = randomAlias()
      val creatorBalance    = sender.dccBalance(aliasCreatorAddr).available
      val creatorEffBalance = sender.dccBalance(aliasCreatorAddr).effective

      sender.broadcastCreateAlias(aliasCreator, alias, minFee, version = v, waitForTx = true)
      sender.dccBalance(aliasCreatorAddr).available shouldBe creatorBalance - minFee
      sender.dccBalance(aliasCreatorAddr).effective shouldBe creatorEffBalance - minFee

      Try(assertGrpcError(sender.broadcastCreateAlias(aliasCreator, alias, minFee, version = v), "Alias already claimed", Code.INVALID_ARGUMENT))
        .getOrElse(
          assertGrpcError(sender.broadcastCreateAlias(aliasCreator, alias, minFee, version = v), "is already in the state", Code.INVALID_ARGUMENT)
        )

      sender.dccBalance(aliasCreatorAddr).available shouldBe creatorBalance - minFee
      sender.dccBalance(aliasCreatorAddr).effective shouldBe creatorEffBalance - minFee
    }
  }

  test("Not able to create aliases to other addresses") {
    for (v <- aliasTxSupportedVersions) {
      val alias            = randomAlias()
      val secondBalance    = sender.dccBalance(secondAddress).available
      val secondEffBalance = sender.dccBalance(secondAddress).effective

      sender.broadcastCreateAlias(aliasCreator, alias, minFee, version = v, waitForTx = true)

      Try(assertGrpcError(sender.broadcastCreateAlias(secondAcc, alias, minFee, version = v), "Alias already claimed", Code.INVALID_ARGUMENT))
        .getOrElse(
          assertGrpcError(sender.broadcastCreateAlias(secondAcc, alias, minFee, version = v), "is already in the state", Code.INVALID_ARGUMENT)
        )

      sender.dccBalance(secondAddress).available shouldBe secondBalance
      sender.dccBalance(secondAddress).effective shouldBe secondEffBalance
    }
  }

  val aliases_names =
    Table(s"aliasName${randomAlias()}", s"aaaa${randomAlias()}", s"....${randomAlias()}", s"123456789.${randomAlias()}", s"@.@-@_@${randomAlias()}")

  aliases_names.foreach { alias =>
    test(s"create alias named $alias") {
      for (v <- aliasTxSupportedVersions) {
        sender.broadcastCreateAlias(aliasCreator, s"$alias$v", minFee, version = v, waitForTx = true)
        sender.resolveAlias(s"$alias$v") shouldBe PBRecipients
          .toAddress(aliasCreatorAddr.toByteArray, AddressScheme.current.chainId)
          .explicitGet()
      }
    }
  }

  val invalid_aliases_names =
    Table(
      ("aliasName", "message"),
      ("", "Alias '' length should be between 4 and 30"),
      ("abc", "Alias 'abc' length should be between 4 and 30"),
      ("morethen_thirtycharactersinline", "Alias 'morethen_thirtycharactersinline' length should be between 4 and 30"),
      ("~!|#$%^&*()_+=\";:/?><|\\][{}", "Alias should contain only following characters: -.0123456789@_abcdefghijklmnopqrstuvwxyz"),
      ("multilnetest\ntest", "Alias should contain only following characters: -.0123456789@_abcdefghijklmnopqrstuvwxyz"),
      ("UpperCaseAliase", "Alias should contain only following characters: -.0123456789@_abcdefghijklmnopqrstuvwxyz")
    )

  forAll(invalid_aliases_names) { (alias: String, message: String) =>
    test(s"Not able to create alias named $alias") {
      for (v <- aliasTxSupportedVersions) {
        assertGrpcError(sender.broadcastCreateAlias(aliasCreator, alias, minFee, version = v), message, Code.INVALID_ARGUMENT)
      }
    }
  }

  test("Able to lease by alias") {
    for (v <- aliasTxSupportedVersions) {
      val (leaser, leaserAddr) = (thirdAcc, thirdAddress)
      val alias                = randomAlias()

      val aliasCreatorBalance    = sender.dccBalance(aliasCreatorAddr).available
      val aliasCreatorEffBalance = sender.dccBalance(aliasCreatorAddr).effective
      val leaserBalance          = sender.dccBalance(leaserAddr).available
      val leaserEffBalance       = sender.dccBalance(leaserAddr).effective

      sender.broadcastCreateAlias(aliasCreator, alias, minFee, version = v, waitForTx = true)
      val leasingAmount = 1.dcc

      sender.broadcastLease(leaser, Recipient().withAlias(alias), leasingAmount, minFee, waitForTx = true)

      sender.dccBalance(aliasCreatorAddr).available shouldBe aliasCreatorBalance - minFee
      sender.dccBalance(aliasCreatorAddr).effective shouldBe aliasCreatorEffBalance + leasingAmount - minFee
      sender.dccBalance(leaserAddr).available shouldBe leaserBalance - leasingAmount - minFee
      sender.dccBalance(leaserAddr).effective shouldBe leaserEffBalance - leasingAmount - minFee
    }
  }

  test("Not able to create alias when insufficient funds") {
    for (v <- aliasTxSupportedVersions) {
      val balance = sender.dccBalance(aliasCreatorAddr).available
      val alias   = randomAlias()
      assertGrpcError(
        sender.broadcastCreateAlias(aliasCreator, alias, balance + minFee, version = v),
        "Accounts balance errors",
        Code.INVALID_ARGUMENT
      )
    }
  }

  private def alphanumericStream: LazyList[Char] = {
    val a = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')
    LazyList.continually(a(ThreadLocalRandom.current().nextInt(62)))
  }

  private def randomAlias(): String = {
    s"testalias.${alphanumericStream.take(9).mkString}".toLowerCase
  }

}
