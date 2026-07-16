package com.decentralchain.history

import com.decentralchain.*
import com.decentralchain.block.{Block, MicroBlock}
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.history.Domain.BlockchainUpdaterExt
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.state.diffs.ENOUGH_AMT
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.{GenesisTransaction, TxHelpers}
import org.scalacheck.Gen

class BlockchainUpdaterLiquidBlockTest extends PropSpec with DomainScenarioDrivenPropertyCheck with BlocksTransactionsHelpers {
  import UnsafeBlocks.*

  private def preconditionsAndPayments(minTx: Int, maxTx: Int): Gen[(Block, Block, Seq[MicroBlock])] =
    for {
      richAccount        <- accountGen
      totalTxNumber      <- Gen.chooseNum(minTx, maxTx)
      txNumberInKeyBlock <- Gen.chooseNum(0, Block.MaxTransactionsPerBlockVer3)
    } yield {
      val allTxs                  = Seq.fill(totalTxNumber)(TxHelpers.transfer(richAccount, version = 1.toByte))
      val (keyBlockTxs, microTxs) = allTxs.splitAt(txNumberInKeyBlock)
      val txNumberInMicros        = totalTxNumber - txNumberInKeyBlock

      val prevBlock = unsafeBlock(
        reference = randomSig,
        txs = Seq(GenesisTransaction.create(richAccount.toAddress, ENOUGH_AMT, 0).explicitGet()),
        signer = TestBlock.defaultSigner,
        version = 3,
        timestamp = 0
      )

      val (keyBlock, microBlocks) = unsafeChainBaseAndMicro(
        totalRefTo = prevBlock.signature,
        base = keyBlockTxs,
        micros = microTxs.grouped((txNumberInMicros / 5) min 500 max 1).toSeq,
        signer = TestBlock.defaultSigner,
        version = 3,
        timestamp = TxHelpers.timestamp
      )

      (prevBlock, keyBlock, microBlocks)
    }

  property("liquid block can't be overfilled") {
    import Block.MaxTransactionsPerBlockVer3 as Max
    forAll(preconditionsAndPayments(Max + 1, Max + 100)) { case (prevBlock, keyBlock, microBlocks) =>
      withDomain(MicroblocksActivatedAt0DCCSettings) { d =>
        val blocksApplied = for {
          _ <- d.blockchainUpdater.processBlock(prevBlock)
          _ <- d.blockchainUpdater.processBlock(keyBlock)
        } yield ()

        val r = microBlocks.foldLeft(blocksApplied) {
          case (Right(_), curr) => d.blockchainUpdater.processMicroBlock(curr, None).map(_ => ())
          case (x, _)           => x
        }

        withClue("All microblocks should not be processed") {
          r match {
            case Left(e: GenericError) => e.err should include("Limit of txs was reached")
            case x                     =>
              val txNumberByMicroBlock = microBlocks.map(_.transactionData.size)
              fail(
                s"Unexpected result: $x. keyblock txs: ${keyBlock.transactionData.length}, " +
                  s"microblock txs: ${txNumberByMicroBlock.mkString(", ")} (total: ${txNumberByMicroBlock.sum}), " +
                  s"total txs: ${keyBlock.transactionData.length + txNumberByMicroBlock.sum}"
              )
          }
        }
      }
    }
  }

  property("miner settings don't interfere with micro block processing") {
    val oneTxPerMicroSettings = MicroblocksActivatedAt0DCCSettings
      .copy(
        minerSettings = MicroblocksActivatedAt0DCCSettings.minerSettings.copy(
          maxTransactionsInMicroBlock = 1
        )
      )
    forAll(preconditionsAndPayments(10, Block.MaxTransactionsPerBlockVer3)) { case (genBlock, keyBlock, microBlocks) =>
      withDomain(oneTxPerMicroSettings) { d =>
        d.blockchainUpdater.processBlock(genBlock)
        d.blockchainUpdater.processBlock(keyBlock)
        microBlocks.foreach { mb =>
          d.blockchainUpdater.processMicroBlock(mb, None) should beRight
        }
      }
    }
  }
}
