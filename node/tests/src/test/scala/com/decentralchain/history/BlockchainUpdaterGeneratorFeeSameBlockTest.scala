package com.decentralchain.history

import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain.BlockchainUpdaterExt
import com.decentralchain.state.diffs.*
import com.decentralchain.test.*
import com.decentralchain.transaction.GenesisTransaction
import com.decentralchain.transaction.transfer.*
import org.scalacheck.Gen

class BlockchainUpdaterGeneratorFeeSameBlockTest extends PropSpec with DomainScenarioDrivenPropertyCheck {

  type Setup = (GenesisTransaction, TransferTransaction, TransferTransaction)

  val preconditionsAndPayments: Gen[Setup] = for {
    sender    <- accountGen
    recipient <- accountGen
    fee       <- smallFeeGen
    ts        <- positiveIntGen
    genesis: GenesisTransaction = GenesisTransaction.create(sender.toAddress, ENOUGH_AMT, ts).explicitGet()
    payment: TransferTransaction <- wavesTransferGeneratorP(ts, sender, recipient.toAddress)
    generatorPaymentOnFee: TransferTransaction = createWavesTransfer(defaultSigner, recipient.toAddress, payment.fee.value, fee, ts + 1).explicitGet()
  } yield (genesis, payment, generatorPaymentOnFee)

  property("block generator can spend fee after transaction before applyMinerFeeWithTransactionAfter") {
    assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
    scenario(preconditionsAndPayments, DefaultDCCSettings) { case (domain, (genesis, somePayment, generatorPaymentOnFee)) =>
      val blocks = chainBlocks(Seq(Seq(genesis), Seq(generatorPaymentOnFee, somePayment)))
      blocks.foreach(block => domain.blockchainUpdater.processBlock(block) should beRight)
    }
  }

  property("block generator can't spend fee after transaction after applyMinerFeeWithTransactionAfter") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0DCCSettings) { case (domain, (genesis, somePayment, generatorPaymentOnFee)) =>
      val blocks = chainBlocks(Seq(Seq(genesis), Seq(generatorPaymentOnFee, somePayment)))
      blocks.init.foreach(block => domain.blockchainUpdater.processBlock(block) should beRight)
      domain.blockchainUpdater.processBlock(blocks.last) should produce("unavailable funds")
    }
  }
}
