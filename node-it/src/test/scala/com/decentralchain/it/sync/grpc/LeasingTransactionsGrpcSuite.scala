package com.decentralchain.it.sync.grpc

import com.google.protobuf.ByteString
import io.decentralchain.api.grpc.LeaseResponse
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.api.SyncGrpcApi.*
import com.decentralchain.it.sync.*
import io.decentralchain.protobuf.transaction.{PBRecipients, PBTransactions, Recipient}
import com.decentralchain.test.*
import com.decentralchain.transaction.Transaction
import com.decentralchain.transaction.lease.LeaseTransaction
import io.grpc.Status.Code

class LeasingTransactionsGrpcSuite extends GrpcBaseTransactionSuite {
  private val errorMessage = "Reason: Cannot lease more than own"

  test("leasing dcc decreases lessor's eff.b. and increases lessee's eff.b.; lessor pays fee") {
    for (v <- leaseTxSupportedVersions) {
      val firstBalance  = sender.dccBalance(firstAddress)
      val secondBalance = sender.dccBalance(secondAddress)

      val leaseTx   = sender.broadcastLease(firstAcc, PBRecipients.create(secondAcc.toAddress), leasingAmount, minFee, version = v, waitForTx = true)
      val vanillaTx = PBTransactions.vanilla(leaseTx, unsafe = false).explicitGet()
      val leaseTxId = vanillaTx.id().toString
      val height    = sender.getStatus(leaseTxId).height

      sender.dccBalance(firstAddress).regular shouldBe firstBalance.regular - minFee
      sender.dccBalance(firstAddress).effective shouldBe firstBalance.effective - minFee - leasingAmount
      sender.dccBalance(secondAddress).regular shouldBe secondBalance.regular
      sender.dccBalance(secondAddress).effective shouldBe secondBalance.effective + leasingAmount

      val response = toResponse(vanillaTx, height)
      sender.getActiveLeases(secondAddress) shouldBe List(response)
      sender.getActiveLeases(firstAddress) shouldBe List(response)

      sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee, waitForTx = true)
    }
  }

  test("cannot lease non-own dcc") {
    for (v <- leaseTxSupportedVersions) {
      val leaseTx   = sender.broadcastLease(firstAcc, PBRecipients.create(secondAcc.toAddress), leasingAmount, minFee, version = v, waitForTx = true)
      val vanillaTx = PBTransactions.vanilla(leaseTx, unsafe = false).explicitGet()
      val leaseTxId = vanillaTx.id().toString
      val height    = sender.getStatus(leaseTxId).height

      val secondEffBalance = sender.dccBalance(secondAddress).effective
      val thirdEffBalance  = sender.dccBalance(thirdAddress).effective

      assertGrpcError(
        sender.broadcastLease(secondAcc, PBRecipients.create(thirdAcc.toAddress), secondEffBalance - minFee, minFee, version = v),
        errorMessage,
        Code.INVALID_ARGUMENT
      )

      sender.dccBalance(secondAddress).effective shouldBe secondEffBalance
      sender.dccBalance(thirdAddress).effective shouldBe thirdEffBalance

      val response = toResponse(vanillaTx, height)
      sender.getActiveLeases(secondAddress) shouldBe List(response)
      sender.getActiveLeases(thirdAddress) shouldBe List.empty

      sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee, waitForTx = true)
    }
  }

  test("can not make leasing without having enough balance") {
    for (v <- leaseTxSupportedVersions) {
      val firstBalance  = sender.dccBalance(firstAddress)
      val secondBalance = sender.dccBalance(secondAddress)

      // secondAddress effective balance more than general balance
      assertGrpcError(
        sender.broadcastLease(secondAcc, Recipient().withPublicKeyHash(firstAddress), secondBalance.regular + 1.dcc, minFee, version = v),
        errorMessage,
        Code.INVALID_ARGUMENT
      )

      assertGrpcError(
        sender.broadcastLease(firstAcc, Recipient().withPublicKeyHash(secondAddress), firstBalance.regular, minFee, version = v),
        "Accounts balance errors",
        Code.INVALID_ARGUMENT
      )

      assertGrpcError(
        sender.broadcastLease(firstAcc, Recipient().withPublicKeyHash(secondAddress), firstBalance.regular - minFee / 2, minFee, version = v),
        "Accounts balance errors",
        Code.INVALID_ARGUMENT
      )

      sender.dccBalance(firstAddress) shouldBe firstBalance
      sender.dccBalance(secondAddress) shouldBe secondBalance
      sender.getActiveLeases(firstAddress) shouldBe List.empty
      sender.getActiveLeases(secondAddress) shouldBe List.empty
    }
  }

  test("lease cancellation reverts eff.b. changes; lessor pays fee for both lease and cancellation") {
    for (v <- leaseTxSupportedVersions) {
      val firstBalance  = sender.dccBalance(firstAddress)
      val secondBalance = sender.dccBalance(secondAddress)

      val leaseTx   = sender.broadcastLease(firstAcc, PBRecipients.create(secondAcc.toAddress), leasingAmount, minFee, version = v, waitForTx = true)
      val leaseTxId = PBTransactions.vanilla(leaseTx, unsafe = false).explicitGet().id().toString

      sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee, waitForTx = true)

      sender.dccBalance(firstAddress).regular shouldBe firstBalance.regular - 2 * minFee
      sender.dccBalance(firstAddress).effective shouldBe firstBalance.effective - 2 * minFee
      sender.dccBalance(secondAddress).regular shouldBe secondBalance.regular
      sender.dccBalance(secondAddress).effective shouldBe secondBalance.effective
      sender.getActiveLeases(secondAddress) shouldBe List.empty
      sender.getActiveLeases(firstAddress) shouldBe List.empty
    }
  }

  test("lease cancellation can be done only once") {
    for (v <- leaseTxSupportedVersions) {
      val firstBalance  = sender.dccBalance(firstAddress)
      val secondBalance = sender.dccBalance(secondAddress)

      val leaseTx   = sender.broadcastLease(firstAcc, PBRecipients.create(secondAcc.toAddress), leasingAmount, minFee, version = v, waitForTx = true)
      val leaseTxId = PBTransactions.vanilla(leaseTx, unsafe = false).explicitGet().id().toString

      sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee, waitForTx = true)

      assertGrpcError(
        sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee),
        "Reason: Cannot cancel already cancelled lease",
        Code.INVALID_ARGUMENT
      )
      sender.dccBalance(firstAddress).regular shouldBe firstBalance.regular - 2 * minFee
      sender.dccBalance(firstAddress).effective shouldBe firstBalance.effective - 2 * minFee
      sender.dccBalance(secondAddress).regular shouldBe secondBalance.regular
      sender.dccBalance(secondAddress).effective shouldBe secondBalance.effective

      sender.getActiveLeases(secondAddress) shouldBe List.empty
      sender.getActiveLeases(firstAddress) shouldBe List.empty
    }
  }

  test("only sender can cancel lease transaction") {
    for (v <- leaseTxSupportedVersions) {
      val firstBalance  = sender.dccBalance(firstAddress)
      val secondBalance = sender.dccBalance(secondAddress)

      val leaseTx   = sender.broadcastLease(firstAcc, PBRecipients.create(secondAcc.toAddress), leasingAmount, minFee, version = v, waitForTx = true)
      val vanillaTx = PBTransactions.vanilla(leaseTx, unsafe = false).explicitGet()
      val leaseTxId = vanillaTx.id().toString
      val height    = sender.getStatus(leaseTxId).height

      assertGrpcError(
        sender.broadcastLeaseCancel(secondAcc, leaseTxId, minFee),
        "LeaseTransaction was leased by other sender",
        Code.INVALID_ARGUMENT
      )
      sender.dccBalance(firstAddress).regular shouldBe firstBalance.regular - minFee
      sender.dccBalance(firstAddress).effective shouldBe firstBalance.effective - minFee - leasingAmount
      sender.dccBalance(secondAddress).regular shouldBe secondBalance.regular
      sender.dccBalance(secondAddress).effective shouldBe secondBalance.effective + leasingAmount

      val response = toResponse(vanillaTx, height)
      sender.getActiveLeases(secondAddress) shouldBe List(response)
      sender.getActiveLeases(firstAddress) shouldBe List(response)

      sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee, waitForTx = true)
    }
  }

  test("can not make leasing to yourself") {
    for (v <- leaseTxSupportedVersions) {
      val firstBalance = sender.dccBalance(firstAddress)
      assertGrpcError(
        sender.broadcastLease(firstAcc, PBRecipients.create(firstAcc.toAddress), leasingAmount, minFee, v),
        "Transaction to yourself",
        Code.INVALID_ARGUMENT
      )
      sender.dccBalance(firstAddress).regular shouldBe firstBalance.regular
      sender.dccBalance(firstAddress).effective shouldBe firstBalance.effective
      sender.getActiveLeases(firstAddress) shouldBe List.empty
    }
  }

  private def toResponse(tx: Transaction, height: Long): LeaseResponse = {
    val leaseTx   = tx.asInstanceOf[LeaseTransaction]
    val leaseTxId = ByteString.copyFrom(leaseTx.id().arr)
    LeaseResponse(
      leaseId = leaseTxId,
      originTransactionId = leaseTxId,
      sender = ByteString.copyFrom(leaseTx.sender.toAddress.bytes),
      recipient = Some(PBRecipients.create(leaseTx.recipient)),
      amount = leaseTx.amount.value,
      height = height.toInt
    )
  }
}
