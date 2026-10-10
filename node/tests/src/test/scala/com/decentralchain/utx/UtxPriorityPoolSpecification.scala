package com.decentralchain.utx

import com.decentralchain.db.WithState
import com.decentralchain.mining.MultiDimensionalMiningConstraint
import com.decentralchain.settings.DCCSettings
import com.decentralchain.test.*
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.utx.UtxPool.PackStrategy

class UtxPriorityPoolSpecification extends FreeSpec with SharedDomain {
  private val alice = TxHelpers.signer(100)

  private var lastKeyPair = 0
  private def nextKeyPair = {
    lastKeyPair += 1
    TxHelpers.signer(lastKeyPair)
  }

  override val genesisBalances: Seq[WithState.AddrWithBalance] = Seq(alice -> 10000.dcc)

  override def settings: DCCSettings = DomainPresets.RideV3

  private def pack() = domain.utxPool.packUnconfirmed(MultiDimensionalMiningConstraint.Unlimited, None, PackStrategy.Unlimited)._1

  "priority pool" - {
    "preserves correct order of transactions" in {
      val id = domain.appendKeyBlock().id()
      val t1 = TxHelpers.transfer(alice, nextKeyPair.toAddress, fee = 0.001.dcc)
      val t2 = TxHelpers.transfer(alice, nextKeyPair.toAddress, fee = 0.01.dcc, timestamp = t1.timestamp - 10000)

      domain.appendMicroBlock(t1)
      domain.appendMicroBlock(t2)
      domain.appendKeyBlock(ref = Some(id))

      val expectedTransactions = Seq(t1, t2)
      domain.utxPool.all shouldBe expectedTransactions
      pack() shouldBe Some(expectedTransactions)
    }

    "tx from last microblock is placed on next height ahead of new txs after appending key block" in {
      domain.utxPool.removeAll(domain.utxPool.nonPriorityTransactions)
      val blockId = domain.appendKeyBlock().id()
      val issue   = TxHelpers.issue(alice)

      domain.appendMicroBlock(issue)
      domain.blockchain.transactionInfo(issue.id()) shouldBe defined
      domain.utxPool.all shouldBe Nil

      domain.appendKeyBlock(ref = Some(blockId))
      domain.blockchain.transactionInfo(issue.id()) shouldBe None
      domain.utxPool.all shouldBe Seq(issue)

      val secondIssue = TxHelpers.issue(alice, fee = 2.dcc)
      domain.utxPool.putIfNew(secondIssue)
      pack() shouldBe Some(List(issue, secondIssue))
    }

    "cleanup keeps a transaction that depends on a priority transaction" in {
      domain.utxPool.removeAll(domain.utxPool.all)
      val blockId = domain.appendKeyBlock().id()
      val bob     = nextKeyPair
      val fund    = TxHelpers.transfer(alice, bob.toAddress, 10.dcc)
      domain.appendMicroBlock(fund)

      // Accepted while the funding transfer is in the liquid microblock...
      val dependent = TxHelpers.transfer(bob, alice.toAddress, 1.dcc)
      domain.utxPool.putIfNew(dependent).resultE shouldBe Right(true)

      // ...which a competing key block then discards: `fund` returns as a priority transaction.
      domain.appendKeyBlock(ref = Some(blockId))
      domain.blockchain.transactionInfo(fund.id()) shouldBe None

      domain.utxPool.cleanUnconfirmed()
      domain.utxPool.all shouldBe Seq(fund, dependent)
      pack() shouldBe Some(Seq(fund, dependent))
    }

    "cleanup still removes a transaction that is invalid even after the priority transactions" in {
      domain.utxPool.removeAll(domain.utxPool.all)
      val blockId = domain.appendKeyBlock().id()
      val bob     = nextKeyPair
      val fund    = TxHelpers.transfer(alice, bob.toAddress, 10.dcc)
      domain.appendMicroBlock(fund)

      val overspend = TxHelpers.transfer(bob, alice.toAddress, 9.dcc)
      val tooMuch   = TxHelpers.transfer(bob, alice.toAddress, 2.dcc)
      domain.utxPool.putIfNew(overspend).resultE shouldBe Right(true)
      domain.utxPool.putIfNew(tooMuch).resultE shouldBe Right(true)

      domain.appendKeyBlock(ref = Some(blockId))
      // Both are individually valid once `fund` is applied, so both stay; packing then admits only
      // what fits, exactly as before this change.
      domain.utxPool.cleanUnconfirmed()
      domain.utxPool.all.toSet shouldBe Set(fund, overspend, tooMuch)

      val unrelated = TxHelpers.transfer(nextKeyPair, alice.toAddress, 1.dcc)
      domain.utxPool.addTransaction(unrelated, verify = false)
      domain.utxPool.cleanUnconfirmed()
      domain.utxPool.all should not contain unrelated
    }
  }
}
