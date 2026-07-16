package com.decentralchain.it.sync.grpc

import com.decentralchain.common.utils.Base64
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.NTPTime
import com.decentralchain.it.api.SyncGrpcApi.*
import com.decentralchain.it.sync.*
import com.decentralchain.lang.script.Script
import io.decentralchain.protobuf.transaction.PBTransactions
import com.decentralchain.test.*
import com.decentralchain.transaction.assets.IssueTransaction
import io.grpc.Status.Code
import org.scalatest.prop.TableDrivenPropertyChecks

import java.util.concurrent.ThreadLocalRandom

class IssueTransactionGrpcSuite extends GrpcBaseTransactionSuite with NTPTime with TableDrivenPropertyChecks {

  val (issuer, issuerAddress) = (firstAcc, firstAddress)

  private def alphanumericStream: LazyList[Char] = {
    val a = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9'); LazyList.continually(a(ThreadLocalRandom.current().nextInt(62)))
  }

  private def randomString(n: Int): String = {
    val a = "abcdefghijklmnopqrstuvwxyz0123456789"; (0 until n).map(_ => a.charAt(ThreadLocalRandom.current().nextInt(a.length))).mkString
  }

  test("asset issue changes issuer's asset balance") {
    for (v <- issueTxSupportedVersions) {
      val assetName        = alphanumericStream.filter(_.isLetter).take(IssueTransaction.MinAssetNameLength).mkString
      val assetDescription = "my asset description"
      val issuerBalance    = sender.dccBalance(issuerAddress).available
      val issuerEffBalance = sender.dccBalance(issuerAddress).effective

      val issuedAssetTx = sender.broadcastIssue(
        issuer,
        assetName,
        someAssetAmount,
        8,
        reissuable = true,
        issueFee,
        assetDescription,
        version = v,
        script = scriptText(v),
        waitForTx = true
      )
      val issuedAssetId = PBTransactions.vanilla(issuedAssetTx, unsafe = false).explicitGet().id().toString

      sender.dccBalance(issuerAddress).available shouldBe issuerBalance - issueFee
      sender.dccBalance(issuerAddress).effective shouldBe issuerEffBalance - issueFee
      sender.assetsBalance(issuerAddress, Seq(issuedAssetId)).getOrElse(issuedAssetId, 0L) shouldBe someAssetAmount

      val assetInfo = sender.getTransaction(issuedAssetId).getDccTransaction.getIssue

      assetInfo.decimals shouldBe 8
      assetInfo.amount shouldBe someAssetAmount
      assetInfo.reissuable shouldBe true
      assetInfo.description shouldBe assetDescription

    }
  }

  test("not able to issue asset with fee less then issueFee (minFee for NFT)") {
    for (v <- issueTxSupportedVersions) {
      val assetName                                 = alphanumericStream.filter(_.isLetter).take(IssueTransaction.MinAssetNameLength + 1).mkString
      val assetDescription                          = "nft asset"
      val issuerBalance                             = sender.dccBalance(issuerAddress).available
      val issuerEffBalance                          = sender.dccBalance(issuerAddress).effective
      val (nftQuantity, nftDecimals, nftReissuable) = (1, 0, false)

      assertGrpcError(
        sender.broadcastIssue(issuer, assetName, someAssetAmount, 7, reissuable = true, issueFee - 1, assetDescription, version = v),
        s"does not exceed minimal value of $issueFee",
        Code.INVALID_ARGUMENT
      )

      assertGrpcError(
        sender.broadcastIssue(issuer, assetName, nftQuantity, nftDecimals, nftReissuable, minFee - 1, assetDescription, version = v),
        s"does not exceed minimal value of $minFee",
        Code.INVALID_ARGUMENT
      )

      sender.dccBalance(issuerAddress).available shouldBe issuerBalance
      sender.dccBalance(issuerAddress).effective shouldBe issuerEffBalance
    }
  }

  test("Able to create asset with the same name") {
    for (v <- issueTxSupportedVersions) {
      val assetName        = alphanumericStream.filter(_.isLetter).take(IssueTransaction.MaxAssetNameLength).mkString
      val assetDescription = "my asset description 2"

      val issuedAssetTx = sender.broadcastIssue(
        issuer,
        assetName,
        someAssetAmount,
        7,
        reissuable = true,
        issueFee,
        assetDescription,
        version = v,
        script = scriptText(v),
        waitForTx = true
      )
      val issuedAssetId = PBTransactions.vanilla(issuedAssetTx, unsafe = false).explicitGet().id().toString

      val issuedAssetTx2 = sender.broadcastIssue(
        issuer,
        assetName,
        someAssetAmount,
        7,
        reissuable = true,
        issueFee,
        assetDescription,
        version = v,
        script = scriptText(v),
        waitForTx = true
      )
      val issuedAssetId2 = PBTransactions.vanilla(issuedAssetTx2, unsafe = false).explicitGet().id().toString

      sender.assetsBalance(issuerAddress, Seq(issuedAssetId)).getOrElse(issuedAssetId, 0L) shouldBe someAssetAmount
      sender.assetsBalance(issuerAddress, Seq(issuedAssetId2)).getOrElse(issuedAssetId2, 0L) shouldBe someAssetAmount

      sender.getTransaction(issuedAssetId).getDccTransaction.getIssue.name shouldBe assetName
      sender.getTransaction(issuedAssetId2).getDccTransaction.getIssue.name shouldBe assetName
    }
  }

  test("Not able to create asset when insufficient funds") {
    val assetName        = "myasset"
    val issuerEffBalance = sender.dccBalance(issuerAddress).effective
    val bigAssetFee      = issuerEffBalance + 1.dcc

    assertGrpcError(
      sender.broadcastIssue(issuer, assetName, someAssetAmount, 8, reissuable = false, bigAssetFee),
      "Accounts balance errors",
      Code.INVALID_ARGUMENT
    )

  }

  val invalidScript =
    Table(
      ("script", "error"),
      ("base64:AQa3b8tZ", "Invalid checksum"),
      ("base64:AA==", "Illegal length of script: 1"),
      ("base64:AAQB", "Invalid content type of script: 4"),
      ("base64:AAEF", "Invalid checksum"),
      ("base64:CgEF", "Invalid version of script: 10")
    )

  forAll(invalidScript) { (script: String, error: String) =>
    test(s"Try to put incorrect script=$script") {
      val assetName = "myasset"

      assertGrpcError(
        sender.broadcastIssue(issuer, assetName, someAssetAmount, 2, reissuable = true, issueFee, script = Left(Base64.decode(script))),
        error,
        Code.INVALID_ARGUMENT
      )
    }
  }

  val invalidAssetValue =
    Table(
      ("assetVal", "decimals", "message"),
      (0L, 2, "non-positive amount"),
      (1L, IssueTransaction.MaxAssetDecimals + 1, "invalid decimals value: 9, decimals should be in interval \\[0; 8\\]"),
      (-1L, 1, "non-positive amount"),
      (1L, -1, "invalid decimals value: -1, decimals should be in interval \\[0; 8\\]")
    )

  forAll(invalidAssetValue) { (assetVal: Long, decimals: Int, error: String) =>
    test(s"Not able to create asset total token='$assetVal', decimals='$decimals' ") {
      val assetName          = "myasset2"
      val decimalBytes: Byte = decimals.toByte
      assertGrpcError(
        sender.broadcastIssue(issuer, assetName, assetVal, decimalBytes, reissuable = false, issueFee),
        s"$error",
        Code.INVALID_ARGUMENT
      )
    }
  }

  val tooSmallAssetName    = alphanumericStream.filter(_.isLetter).take(IssueTransaction.MinAssetNameLength - 1).mkString
  val tooBigAssetName      = alphanumericStream.filter(_.isLetter).take(IssueTransaction.MaxAssetNameLength + 1).mkString
  val invalid_assets_names =
    Table(
      tooSmallAssetName,
      tooBigAssetName,
      "~!|#$%^&*()_+=\";:/?><|\\][{}"
    )

  forAll(invalid_assets_names) { (invalidAssetName: String) =>
    test(s"Not able to create asset named $invalidAssetName") {
      assertGrpcError(
        sender.broadcastIssue(issuer, invalidAssetName, someAssetAmount, 2, reissuable = false, issueFee),
        "invalid name",
        Code.INVALID_ARGUMENT
      )
    }
  }

  test("Not able to create asset with too big description") {
    val tooBigDescription = randomString(1000 + 1)
    assertGrpcError(
      sender.broadcastIssue(issuer, "assetName", someAssetAmount, 2, description = tooBigDescription, reissuable = false, fee = issueFee),
      "Too big sequence requested",
      Code.INVALID_ARGUMENT
    )
  }

  def scriptText(version: Int): Either[Array[Byte], Option[Script]] =
    Right(version match {
      case 2 => Some(script)
      case _ => None
    })
}
