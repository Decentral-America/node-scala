package com.decentralchain.state.diffs

import com.decentralchain.db.WithState
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.settings.{FunctionalitySettings, TestFunctionalitySettings}
import com.decentralchain.test.*
import com.decentralchain.transaction.{GenesisTransaction, PaymentTransaction, TxHelpers}

class PaymentTransactionDiffTest extends PropSpec with WithState {

  val preconditionsAndPayments: Seq[(GenesisTransaction, PaymentTransaction, PaymentTransaction)] = {
    val master = TxHelpers.signer(1)
    Seq(master, TxHelpers.signer(2)).map { recipient =>
      val genesis   = TxHelpers.genesis(master.toAddress)
      val paymentV2 = TxHelpers.payment(master, recipient.toAddress)
      val paymentV3 = TxHelpers.payment(master, recipient.toAddress)

      (genesis, paymentV2, paymentV3)
    }
  }

  val settings: FunctionalitySettings = TestFunctionalitySettings.Enabled.copy(blockVersion3AfterHeight = 2)

  property("StateSnapshot doesn't break invariant before block version 3") {
    preconditionsAndPayments.foreach { case (genesis, paymentV2, _) =>
      assertDiffAndState(Seq(TestBlock.create(Seq(genesis))), TestBlock.create(Seq(paymentV2)), settings) { (blockDiff, blockchain) =>
        blockDiff.balances.map { case ((address, asset), amount) => blockchain.balance(address, asset) - amount }.sum shouldBe 0
        blockDiff.leaseBalances shouldBe empty
      }
    }
  }

  property("Validation fails with block version 3") {
    preconditionsAndPayments.foreach { case ((genesis, paymentV2, paymentV3)) =>
      assertDiffEi(Seq(TestBlock.create(Seq(genesis)), TestBlock.create(Seq(paymentV2))), TestBlock.create(Seq(paymentV3)), settings) { blockDiffEi =>
        blockDiffEi should produce(s"Payment transaction is deprecated after h=${settings.blockVersion3AfterHeight}")
      }
    }
  }
}
