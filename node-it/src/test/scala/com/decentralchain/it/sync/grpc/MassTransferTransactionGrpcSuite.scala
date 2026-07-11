package com.decentralchain.it.sync.grpc

import com.google.protobuf.ByteString
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.api.SyncGrpcApi.*
import com.decentralchain.it.sync.*
import io.decentralchain.protobuf.transaction.MassTransferTransactionData.Transfer
import io.decentralchain.protobuf.transaction.{PBTransactions, Recipient}
import com.decentralchain.transaction.transfer.MassTransferTransaction.MaxTransferCount
import com.decentralchain.transaction.transfer.TransferTransaction.MaxAttachmentSize
import io.grpc.Status.Code

class MassTransferTransactionGrpcSuite extends GrpcBaseTransactionSuite {

  test("asset mass transfer changes asset balances and sender's.dcc balance is decreased by fee.") {
    for (v <- massTransferTxSupportedVersions) {
      val firstBalance  = sender.dccBalance(firstAddress)
      val secondBalance = sender.dccBalance(secondAddress)
      val attachment    = ByteString.copyFrom("mass transfer description".getBytes("UTF-8"))

      val transfers = List(Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(secondAddress))), transferAmount))
      val assetId   = PBTransactions
        .vanilla(
          sender.broadcastIssue(firstAcc, "name", issueAmount, 8, reissuable = false, issueFee, waitForTx = true),
          true
        )
        .explicitGet()
        .id()
        .toString
      sender.waitForTransaction(assetId)

      val massTransferTransactionFee = calcMassTransferFee(transfers.size)
      sender.broadcastMassTransfer(firstAcc, Some(assetId), transfers, attachment, massTransferTransactionFee, waitForTx = true, version = v)

      val firstBalanceAfter  = sender.dccBalance(firstAddress)
      val secondBalanceAfter = sender.dccBalance(secondAddress)

      firstBalanceAfter.regular shouldBe firstBalance.regular - issueFee - massTransferTransactionFee
      firstBalanceAfter.effective shouldBe firstBalance.effective - issueFee - massTransferTransactionFee
      sender.assetsBalance(firstAddress, Seq(assetId)).getOrElse(assetId, 0L) shouldBe issueAmount - transferAmount
      secondBalanceAfter.regular shouldBe secondBalance.regular
      secondBalanceAfter.effective shouldBe secondBalance.effective
      sender.assetsBalance(secondAddress, Seq(assetId)).getOrElse(assetId, 0L) shouldBe transferAmount
    }
  }

  test("dcc mass transfer changes dcc balances") {
    val firstBalance  = sender.dccBalance(firstAddress)
    val secondBalance = sender.dccBalance(secondAddress)
    val thirdBalance  = sender.dccBalance(thirdAddress)
    val transfers     = List(
      Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(secondAddress))), transferAmount),
      Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(thirdAddress))), 2 * transferAmount)
    )

    val massTransferTransactionFee = calcMassTransferFee(transfers.size)
    sender.broadcastMassTransfer(firstAcc, transfers = transfers, fee = massTransferTransactionFee, waitForTx = true)

    val firstBalanceAfter  = sender.dccBalance(firstAddress)
    val secondBalanceAfter = sender.dccBalance(secondAddress)
    val thirdBalanceAfter  = sender.dccBalance(thirdAddress)

    firstBalanceAfter.regular shouldBe firstBalance.regular - massTransferTransactionFee - 3 * transferAmount
    firstBalanceAfter.effective shouldBe firstBalance.effective - massTransferTransactionFee - 3 * transferAmount
    secondBalanceAfter.regular shouldBe secondBalance.regular + transferAmount
    secondBalanceAfter.effective shouldBe secondBalance.effective + transferAmount
    thirdBalanceAfter.regular shouldBe thirdBalance.regular + 2 * transferAmount
    thirdBalanceAfter.effective shouldBe thirdBalance.effective + 2 * transferAmount
  }

  test("can not make mass transfer without having enough dcc") {
    val firstBalance  = sender.dccBalance(firstAddress)
    val secondBalance = sender.dccBalance(secondAddress)
    val transfers     = List(
      Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(secondAddress))), firstBalance.regular / 2),
      Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(thirdAddress))), firstBalance.regular / 2)
    )

    assertGrpcError(
      sender.broadcastMassTransfer(firstAcc, transfers = transfers, fee = calcMassTransferFee(transfers.size)),
      "Attempt to transfer unavailable funds",
      Code.INVALID_ARGUMENT
    )

    nodes.foreach(n => n.waitForHeight(n.height + 1))
    sender.dccBalance(firstAddress) shouldBe firstBalance
    sender.dccBalance(secondAddress) shouldBe secondBalance
  }

  test("cannot make mass transfer when fee less then minimal ") {
    val firstBalance               = sender.dccBalance(firstAddress)
    val secondBalance              = sender.dccBalance(secondAddress)
    val transfers                  = List(Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(secondAddress))), transferAmount))
    val massTransferTransactionFee = calcMassTransferFee(transfers.size)

    assertGrpcError(
      sender.broadcastMassTransfer(firstAcc, transfers = transfers, fee = massTransferTransactionFee - 1),
      s"does not exceed minimal value of $massTransferTransactionFee DCC",
      Code.INVALID_ARGUMENT
    )

    nodes.foreach(n => n.waitForHeight(n.height + 1))
    sender.dccBalance(firstAddress) shouldBe firstBalance
    sender.dccBalance(secondAddress) shouldBe secondBalance
  }

  test("cannot make mass transfer without having enough of effective balance") {
    val firstBalance  = sender.dccBalance(firstAddress)
    val secondBalance = sender.dccBalance(secondAddress)
    val transfers     = List(Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(secondAddress))), firstBalance.regular - 2 * minFee))
    val massTransferTransactionFee = calcMassTransferFee(transfers.size)

    sender.broadcastLease(firstAcc, Recipient.of(Recipient.Recipient.PublicKeyHash(secondAddress)), leasingAmount, minFee, waitForTx = true)

    assertGrpcError(
      sender.broadcastMassTransfer(firstAcc, transfers = transfers, fee = massTransferTransactionFee),
      "Attempt to transfer unavailable funds",
      Code.INVALID_ARGUMENT
    )
    nodes.foreach(n => n.waitForHeight(n.height + 1))
    sender.dccBalance(firstAddress).regular shouldBe firstBalance.regular - minFee
    sender.dccBalance(firstAddress).effective shouldBe firstBalance.effective - minFee - leasingAmount
    sender.dccBalance(secondAddress).regular shouldBe secondBalance.regular
    sender.dccBalance(secondAddress).effective shouldBe secondBalance.effective + leasingAmount
  }

  test("cannot broadcast invalid mass transfer tx") {
    val firstBalance    = sender.dccBalance(firstAddress)
    val secondBalance   = sender.dccBalance(secondAddress)
    val defaultTransfer = List(Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(secondAddress))), transferAmount))

    val negativeTransfer = List(Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(secondAddress))), -1))
    assertGrpcError(
      sender.broadcastMassTransfer(firstAcc, transfers = negativeTransfer, fee = calcMassTransferFee(negativeTransfer.size)),
      "negative amount: -1 of asset",
      Code.INVALID_ARGUMENT
    )

    val tooManyTransfers = List.fill(MaxTransferCount + 1)(Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(secondAddress))), 1))
    assertGrpcError(
      sender.broadcastMassTransfer(firstAcc, transfers = tooManyTransfers, fee = calcMassTransferFee(MaxTransferCount + 1)),
      s"Number of transfers ${MaxTransferCount + 1} is greater than 100",
      Code.INVALID_ARGUMENT
    )

    val tooBigAttachment = ByteString.copyFrom(("a" * (MaxAttachmentSize + 1)).getBytes("UTF-8"))
    assertGrpcError(
      sender.broadcastMassTransfer(firstAcc, transfers = defaultTransfer, attachment = tooBigAttachment, fee = calcMassTransferFee(1)),
      s"Invalid attachment. Length ${MaxAttachmentSize + 1} bytes exceeds maximum of $MaxAttachmentSize bytes.",
      Code.INVALID_ARGUMENT
    )

    sender.dccBalance(firstAddress) shouldBe firstBalance
    sender.dccBalance(secondAddress) shouldBe secondBalance
  }

  test("huge transactions are allowed") {
    val firstBalance  = sender.dccBalance(firstAddress)
    val fee           = calcMassTransferFee(MaxTransferCount)
    val amount        = (firstBalance.available - fee) / MaxTransferCount
    val maxAttachment = ByteString.copyFrom(("a" * MaxAttachmentSize).getBytes("UTF-8"))

    val transfers = List.fill(MaxTransferCount)(Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(firstAddress))), amount))
    sender.broadcastMassTransfer(firstAcc, transfers = transfers, fee = fee, attachment = maxAttachment, waitForTx = true)

    sender.dccBalance(firstAddress).regular shouldBe firstBalance.regular - fee
    sender.dccBalance(firstAddress).effective shouldBe firstBalance.effective - fee
  }

  test("able to mass transfer to alias") {
    val firstBalance  = sender.dccBalance(firstAddress)
    val secondBalance = sender.dccBalance(secondAddress)

    val alias = "masstest_alias"

    sender.broadcastCreateAlias(secondAcc, alias, minFee, waitForTx = true)

    val transfers =
      List(
        Transfer(Some(Recipient.of(Recipient.Recipient.PublicKeyHash(firstAddress))), transferAmount),
        Transfer(Some(Recipient.of(Recipient.Recipient.Alias(alias))), transferAmount)
      )

    val massTransferTransactionFee = calcMassTransferFee(transfers.size)
    sender.broadcastMassTransfer(firstAcc, transfers = transfers, fee = massTransferTransactionFee, waitForTx = true)

    sender.dccBalance(firstAddress).regular shouldBe firstBalance.regular - massTransferTransactionFee - transferAmount
    sender.dccBalance(firstAddress).effective shouldBe firstBalance.effective - massTransferTransactionFee - transferAmount
    sender.dccBalance(secondAddress).regular shouldBe secondBalance.regular + transferAmount - minFee
    sender.dccBalance(secondAddress).effective shouldBe secondBalance.effective + transferAmount - minFee
  }
}
