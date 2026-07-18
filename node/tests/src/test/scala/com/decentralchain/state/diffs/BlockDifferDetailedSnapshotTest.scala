package com.decentralchain.state.diffs

import com.decentralchain.account.Address
import com.decentralchain.block.Block
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.{WithDomain, WithState}
import com.decentralchain.history.defaultSigner
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.mining.MiningConstraint
import com.decentralchain.settings.DCCSettings
import com.decentralchain.state.StateSnapshot
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.{NG, RideV6, ScriptsAndSponsorship, SettingsFromDefaultConfig}
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.TxHelpers.defaultAddress
import com.decentralchain.transaction.{TxHelpers, TxVersion}

class BlockDifferDetailedSnapshotTest extends FreeSpec with WithState with WithDomain {
  private def assertDetailedSnapshot(block: Block, ws: DCCSettings)(
      assertion: (StateSnapshot, StateSnapshot) => Unit
  ): Unit =
    withDomain(ws) { d =>
      val BlockDiffer.Result(snapshot, _, _, _, detailedSnapshot, _) =
        BlockDiffer
          .fromBlock(d.blockchain, Some(d.lastBlock), block, None, MiningConstraint.Unlimited, block.header.generationSignature)
          .explicitGet()
      assertion(snapshot, detailedSnapshot)
    }

  "BlockDiffer DetailedSnapshot" - {
    "works in case of one genesis transaction" in {
      val genesisBlock: (Address, Block) = {
        val master       = TxHelpers.signer(1)
        val genesisBlock = TestBlock.create(System.currentTimeMillis(), Seq(TxHelpers.genesis(master.toAddress))).block
        (master.toAddress, genesisBlock)
      }

      val (master, b) = genesisBlock
      assertDetailedSnapshot(b, RideV6) { case (snapshot, keyBlockSnapshot) =>
        snapshot.balances((master, Dcc)) shouldBe ENOUGH_AMT
        keyBlockSnapshot.balances.get((master, Dcc)) shouldBe None
        keyBlockSnapshot.transactions.size shouldBe 1
        keyBlockSnapshot.transactions.head._2.snapshot.balances((master, Dcc)) shouldBe ENOUGH_AMT
      }
    }

    "genesis and transfers" - {
      val fee1 = 999999
      val fee2 = 100000

      val gAmount = 30.dcc
      val amount1 = 15.dcc
      val amount2 = 7.dcc

      val a1 = TxHelpers.signer(1)
      val a2 = TxHelpers.signer(2)

      val genesis   = TxHelpers.genesis(a1.toAddress, gAmount)
      val transfer1 = TxHelpers.transfer(a1, a2.toAddress, amount1, fee = fee1, version = TxVersion.V1)
      val transfer2 = TxHelpers.transfer(a2, a1.toAddress, amount2, fee = fee2, version = TxVersion.V1)
      val block     = TestBlock.create(a1, Seq(genesis, transfer1, transfer2))
      val address1  = a1.toAddress
      val address2  = a2.toAddress

      "transaction snapshots are correct" in {
        assertDetailedSnapshot(block.block, RideV6) { case (_, keyBlockSnapshot) =>
          val transactionSnapshots = keyBlockSnapshot.transactions.map(_._2.snapshot).toSeq
          transactionSnapshots(0).balances((address1, Dcc)) shouldBe gAmount
          transactionSnapshots(1).balances((address1, Dcc)) shouldBe gAmount - amount1 - fee1 + fee1 / 5 * 2
          transactionSnapshots(1).balances((address2, Dcc)) shouldBe amount1
          transactionSnapshots(2).balances((address1, Dcc)) shouldBe gAmount - amount1 - fee1 + fee1 / 5 * 2 + amount2 + fee2 / 5 * 2
          transactionSnapshots(2).balances((address2, Dcc)) shouldBe amount1 - amount2 - fee2
        }
      }

      "miner reward is correct" - {
        "without NG" in {
          assertDetailedSnapshot(block.block, SettingsFromDefaultConfig) { case (_, keyBlockSnapshot) =>
            keyBlockSnapshot.balances((address1, Dcc)) shouldBe fee1 + fee2
          }
        }

        "with NG" - {
          "no history — no reward" in {
            assertDetailedSnapshot(block.block, NG) { case (_, keyBlockSnapshot) =>
              keyBlockSnapshot.balances shouldBe empty
            }
          }

          // The miner ends up with all of the previous block's fee: 40% immediately (as that block's miner)
          // plus the 60% NG carry credited in this block's key-block snapshot. The carry is only produced
          // when FeeSponsorship is active as well as NG (BlockDiffer: `hasNg && hasSponsorship`), so this
          // uses ScriptsAndSponsorship (NG + sponsorship, as on the live chain) rather than NG alone.
          "with history — all fee from last" in {
            val a1 = TxHelpers.signer(1)
            val a2 = TxHelpers.signer(2)

            val amount1 = 2.dcc
            val amount2 = 1.dcc

            val genesis   = TxHelpers.genesis(a1.toAddress)
            val transfer1 = TxHelpers.transfer(a1, a2.toAddress, amount1, fee = fee1, version = TxVersion.V1)
            val transfer2 = TxHelpers.transfer(a2, a1.toAddress, amount2, fee = fee2, version = TxVersion.V1)

            withDomain(ScriptsAndSponsorship) { d =>
              d.appendBlock(genesis)
              d.appendBlock(transfer1)
              val block = TestBlock.create(defaultSigner, Seq(transfer2)).block
              val BlockDiffer.Result(_, _, _, _, detailedSnapshot, _) =
                BlockDiffer
                  .fromBlock(d.blockchain, Some(d.lastBlock), block, None, MiningConstraint.Unlimited, block.header.generationSignature)
                  .explicitGet()
              detailedSnapshot.balances((defaultAddress, Dcc)) shouldBe fee1
            }
          }
        }
      }
    }
  }
}
