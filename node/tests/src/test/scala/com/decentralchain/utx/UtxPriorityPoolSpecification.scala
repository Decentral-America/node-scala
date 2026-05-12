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

  override val genesisBalances: Seq[WithState.AddrWithBalance] = Seq(alice -> 10000.waves)

  override def settings: DCCSettings = DomainPresets.RideV3

  private def pack() = domain.utxPool.packUnconfirmed(MultiDimensionalMiningConstraint.Unlimited, None, PackStrategy.Unlimited)._1

  "priority pool" - {
    "preserves correct order of transactions" in {
      val id = domain.appendKeyBlock().id()
      val t1 = TxHelpers.transfer(alice, nextKeyPair.toAddress, fee = 0.001.waves)
      val t2 = TxHelpers.transfer(alice, nextKeyPair.toAddress, fee = 0.01.waves, timestamp = t1.timestamp - 10000)

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

      val secondIssue = TxHelpers.issue(alice, fee = 2.waves)
      domain.utxPool.putIfNew(secondIssue)
      pack() shouldBe Some(List(issue, secondIssue))
    }
  }
}
